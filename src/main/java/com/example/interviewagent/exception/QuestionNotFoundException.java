package com.example.interviewagent.exception;

public class QuestionNotFoundException extends RuntimeException {
    public QuestionNotFoundException(long id) {
        super("题目不存在：" + id);
    }
}
