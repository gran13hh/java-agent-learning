package com.example.interviewagent.ai;

import com.example.interviewagent.exception.AiDeadlineException;
import java.time.Duration;

/** 同步调用链的截止时间；ThreadLocal 只对当前请求生效，退出时必须恢复，避免线程池复用污染。 */
public final class CallDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Long previous;
    private CallDeadline(Duration budget) {
        previous = DEADLINE.get();
        long next = System.nanoTime() + budget.toNanos();
        DEADLINE.set(previous == null ? next : Math.min(previous, next));
    }
    public static CallDeadline within(Duration budget) { return new CallDeadline(budget); }
    public static void check() { remaining(Duration.ofSeconds(45)); }
    public static Duration remaining(Duration limit) {
        Long until = DEADLINE.get();
        if (until == null) return limit;
        long remaining = until - System.nanoTime();
        if (remaining <= 0) throw new AiDeadlineException();
        return Duration.ofNanos(Math.min(limit.toNanos(), remaining));
    }
    @Override public void close() { if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous); }
}
