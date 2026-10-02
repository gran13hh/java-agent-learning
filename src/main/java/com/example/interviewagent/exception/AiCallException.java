package com.example.interviewagent.exception;

/** 只携带可向用户展示的信息；不附加可能包含密钥/提示词的上游原始异常。 */
public class AiCallException extends RuntimeException {
    public AiCallException(String message) { super(message); }
}
