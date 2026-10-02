package com.example.interviewagent.repository;

import com.example.interviewagent.domain.*;
import java.util.List;
import java.util.Optional;

public interface InterviewRepository {
    InterviewSession create(Topic topic, List<Question> questions);
    Optional<InterviewSession> find(long id);
    Optional<InterviewSession> findForUpdate(long id);
    List<InterviewTurn> turns(long sessionId);
    void saveAnswer(long turnId, String answer, String feedback, String mode);
    void complete(long sessionId);
    List<InterviewSession> findPage(int limit, long offset);
    long count();
}
