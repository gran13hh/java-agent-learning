package com.example.interviewagent.service;

import com.example.interviewagent.domain.InterviewTurn;

/** 业务依赖接口。V2 可接入模型，但还需把远程调用移出持有行锁的事务。 */
public interface AnswerFeedbackProvider {
    Feedback evaluate(InterviewTurn question, String answer);

    record Feedback(String mode, String text) {
    }
}
