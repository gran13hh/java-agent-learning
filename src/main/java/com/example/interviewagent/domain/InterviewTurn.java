package com.example.interviewagent.domain;

import java.time.LocalDateTime;

/** 数据库存储模型含参考答案；不能直接作为 HTTP 响应返回。 */
public record InterviewTurn(long id, long sessionId, int position, long questionId, String title,
                            Topic topic, Difficulty difficulty, String referenceAnswer, String answer,
                            String feedback, String feedbackMode, LocalDateTime answeredAt) {
    public boolean answered() {
        return answeredAt != null;
    }
}
