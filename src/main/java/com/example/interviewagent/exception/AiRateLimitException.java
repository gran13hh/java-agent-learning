package com.example.interviewagent.exception;

public class AiRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;
    public AiRateLimitException(long seconds) {
        super("模型请求已达限额，请在 " + Math.max(1, seconds) + " 秒后重试");
        this.retryAfterSeconds = Math.max(1, seconds);
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
