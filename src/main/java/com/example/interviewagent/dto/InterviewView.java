package com.example.interviewagent.dto;

import com.example.interviewagent.domain.*;
import java.time.LocalDateTime;
import java.util.List;

public record InterviewView(long id, Topic topic, InterviewStatus status, int questionCount,
                            int answeredCount, LocalDateTime createdAt, LocalDateTime completedAt,
                            List<TurnView> turns) {
    public static InterviewView from(InterviewSession session, List<InterviewTurn> turns) {
        return new InterviewView(session.id(), session.topic(), session.status(), session.questionCount(),
                (int) turns.stream().filter(InterviewTurn::answered).count(), session.createdAt(),
                session.completedAt(), turns.stream().map(TurnView::from).toList());
    }

    /** 未作答时 referenceAnswer 必须为空，避免页面隐藏了答案、接口却提前泄露。 */
    public record TurnView(long id, int position, String title, Topic topic, Difficulty difficulty,
                           String answer, String referenceAnswer, String feedback, String feedbackMode,
                           LocalDateTime answeredAt) {
        static TurnView from(InterviewTurn turn) {
            return new TurnView(turn.id(), turn.position(), turn.title(), turn.topic(), turn.difficulty(),
                    turn.answer(), turn.answered() ? turn.referenceAnswer() : null,
                    turn.feedback(), turn.feedbackMode(), turn.answeredAt());
        }
    }
}
