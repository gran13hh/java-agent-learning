package com.example.interviewagent.exception;

public class AiDeadlineException extends RuntimeException {
    public AiDeadlineException() { super("本次 Agent 已达到 90 秒执行预算，已停止后续调用"); }
}
