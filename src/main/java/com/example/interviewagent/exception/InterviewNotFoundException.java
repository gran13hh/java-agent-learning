package com.example.interviewagent.exception;

public class InterviewNotFoundException extends RuntimeException {
    public InterviewNotFoundException(long id) {
        super("面试记录不存在：" + id);
    }
}
