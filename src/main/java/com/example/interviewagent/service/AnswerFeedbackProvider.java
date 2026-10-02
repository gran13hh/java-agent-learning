package com.example.interviewagent.service;

import com.example.interviewagent.domain.InterviewTurn;

/** 提交回答时的本地策略：Mock 或等待 AI 的占位反馈。此接口不能执行网络请求。 */
public interface AnswerFeedbackProvider {
    Feedback evaluate(InterviewTurn question, String answer);

    record Feedback(String mode, String text) {
    }
}
