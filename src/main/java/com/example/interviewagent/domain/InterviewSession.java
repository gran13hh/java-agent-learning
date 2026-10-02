package com.example.interviewagent.domain;

import java.time.LocalDateTime;

public record InterviewSession(long id, Topic topic, int questionCount, InterviewStatus status,
                               LocalDateTime createdAt, LocalDateTime completedAt) {
}
