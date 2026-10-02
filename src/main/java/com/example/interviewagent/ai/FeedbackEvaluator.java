package com.example.interviewagent.ai;

import com.example.interviewagent.domain.InterviewTurn;

public interface FeedbackEvaluator {
    String evaluate(InterviewTurn turn);
}
