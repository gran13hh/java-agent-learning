package com.example.interviewagent;

import com.example.interviewagent.agent.*;
import com.example.interviewagent.controller.AgentProgressController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AgentProgressTest {
    private AgentRepository.Detail detail(String state) {
        return new AgentRepository.Detail(new AgentRepository.Run(1,"request","目标",null,state,state.equals("SUCCEEDED") ? "建议" : null,
                "状态",1,0,0,LocalDateTime.of(2026,10,4,0,0),null),List.of());
    }
    @Test void terminalSnapshotClosesAndReconnectOnlyReadsSnapshot() throws Exception {
        var reads = new AtomicInteger();
        try (var stream = new StreamResource(new AgentProgressStream(id -> { reads.incrementAndGet(); return detail("SUCCEEDED"); },10,2))) {
            var mvc = MockMvcBuilders.standaloneSetup(new AgentProgressController(stream.value)).build();
            for (int i=0;i<2;i++) {
                var result = mvc.perform(get("/api/agent/runs/1/events").header("Last-Event-ID","old-event")).andReturn();
                result.getAsyncResult(3000);
                String body=result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(body.contains("event:snapshot")); assertTrue(body.contains("event:done")); assertTrue(body.contains("SUCCEEDED"));
                assertEquals("no-cache",result.getResponse().getHeader("Cache-Control"));
            }
            assertEquals(4,reads.get());
        }
    }
    @Test void runningChangesArePushedAndConnectionCapacityIsBounded() throws Exception {
        var state=new AtomicReference<>("RUNNING");
        try(var stream=new StreamResource(new AgentProgressStream(id->detail(state.get()),10,1))) {
            var mvc=MockMvcBuilders.standaloneSetup(new AgentProgressController(stream.value)).build();
            var result=mvc.perform(get("/api/agent/runs/1/events")).andReturn();
            assertThrows(ResponseStatusException.class,()->stream.value.open(1));
            state.set("SUCCEEDED"); result.getAsyncResult(3000);
            assertTrue(result.getResponse().getContentAsString().contains("event:done"));
        }
    }
    @Test void shutdownInterruptsObserverAndReleasesSlot() throws Exception {
        var observed=new CountDownLatch(2);
        var stream=new AgentProgressStream(id->{observed.countDown();return detail("RUNNING");},10,1);
        var mvc=MockMvcBuilders.standaloneSetup(new AgentProgressController(stream)).build();
        mvc.perform(get("/api/agent/runs/1/events"));
        assertTrue(observed.await(2,TimeUnit.SECONDS)); stream.close();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(stream.activeConnections()!=0 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(0,stream.activeConnections());
        assertThrows(ResponseStatusException.class,()->stream.open(1));
    }
    @Test void servletErrorAndTimeoutCallbacksStopObserver() throws Exception {
        for (boolean timeout : List.of(false,true)) {
            var ready = new CountDownLatch(2);
            try (var resource = new StreamResource(new AgentProgressStream(id -> { ready.countDown(); return detail("RUNNING"); },10,1))) {
                var mvc = MockMvcBuilders.standaloneSetup(new AgentProgressController(resource.value)).build();
                var result = mvc.perform(get("/api/agent/runs/1/events")).andReturn();
                assertTrue(ready.await(2,TimeUnit.SECONDS));
                var context = (org.springframework.mock.web.MockAsyncContext) result.getRequest().getAsyncContext();
                var event = new jakarta.servlet.AsyncEvent(context, new java.io.IOException("test disconnect"));
                for (var listener : List.copyOf(context.getListeners())) {
                    if (timeout) listener.onTimeout(event); else listener.onError(event);
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(resource.value.activeConnections()!=0 && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(0,resource.value.activeConnections());
            }
        }
    }
    private record StreamResource(AgentProgressStream value) implements AutoCloseable { public void close(){value.close();} }
}
