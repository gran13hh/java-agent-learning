package com.example.interviewagent.service;

import com.example.interviewagent.domain.InterviewTurn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 提交回答的事务仅存本地占位状态；AI 请求由独立端点在事务外发起。 */
@Component
@ConditionalOnProperty(name = "app.ai.feedback-mode", havingValue = "AI", matchIfMissing = true)
public class PendingAnswerFeedbackProvider implements AnswerFeedbackProvider {
    @Override
    public Feedback evaluate(InterviewTurn question, String answer) {
        return new Feedback("PENDING", "回答已保存，等待生成 AI 反馈。");
    }
}
