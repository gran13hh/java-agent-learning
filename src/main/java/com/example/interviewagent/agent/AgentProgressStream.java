package com.example.interviewagent.agent;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

/** SSE 只观察 MySQL，不执行 Agent。完整快照支持跨实例/重连；不是 token 流或持久化事件日志。 */
@Component
public class AgentProgressStream {
    private final LongFunction<AgentRepository.Detail> read;
    private final long intervalMillis;
    private final Semaphore slots;
    private final Set<Thread> observers = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;
    @Autowired public AgentProgressStream(AgentService service) { this(service::get, 1000, 20); }
    public AgentProgressStream(LongFunction<AgentRepository.Detail> read, long intervalMillis, int maxConnections) {
        this.read = read; this.intervalMillis = intervalMillis; this.slots = new Semaphore(maxConnections);
    }
    public SseEmitter open(long id) {
        read.apply(id); // 先验证 ID，404 在切换 text/event-stream 之前正常返回。
        if (shuttingDown || !slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "进度连接已满，请稍后刷新");
        var emitter = new SseEmitter(180_000L);
        var closed = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().name("agent-progress-" + id).unstarted(() -> {
            try {
                AgentRepository.Detail previous = null;
                long heartbeat = System.nanoTime(), deadline = heartbeat + TimeUnit.SECONDS.toNanos(175);
                while (!closed.get() && System.nanoTime() < deadline) {
                    var detail = read.apply(id);
                    boolean terminal = !Set.of("PENDING", "RUNNING").contains(detail.run().state());
                    if (!detail.equals(previous)) {
                        // 不用递增 event ID 假装支持逐事件回放：每次连接先发最新完整快照。
                        emitter.send(SseEmitter.event().name("snapshot").reconnectTime(3000).data(detail, MediaType.APPLICATION_JSON));
                        previous = detail;
                    }
                    if (terminal) { emitter.send(SseEmitter.event().name("done").data("complete")); break; }
                    if (System.nanoTime() - heartbeat > TimeUnit.SECONDS.toNanos(15)) {
                        emitter.send(SseEmitter.event().comment("heartbeat")); heartbeat = System.nanoTime();
                    }
                    Thread.sleep(intervalMillis);
                }
                if (!closed.get()) emitter.complete();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException | IllegalStateException disconnected) { /* 容器处理连接断开，绝不影响模型任务。 */ }
            catch (RuntimeException failure) {
                try { emitter.send(SseEmitter.event().name("unavailable").data("进度读取失败，请稍后刷新")); emitter.complete(); }
                catch (IOException | IllegalStateException disconnected) { /* 已断开。 */ }
            } finally { closed.set(true); observers.remove(Thread.currentThread()); slots.release(); }
        });
        Runnable cleanup = () -> { closed.set(true); worker.interrupt(); };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(error -> cleanup.run());
        observers.add(worker);
        worker.start();
        return emitter;
    }
    public int activeConnections() { return observers.size(); }
    @PreDestroy public void close() { shuttingDown = true; observers.forEach(Thread::interrupt); }
}
