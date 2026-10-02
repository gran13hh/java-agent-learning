package com.example.interviewagent;

import com.example.interviewagent.domain.*;
import com.example.interviewagent.repository.InterviewRepository;
import java.time.LocalDateTime;
import java.util.*;

/** HTTP 测试替身：不模拟事务/锁；这些行为交给真实 MySQL 测试验证。 */
class InMemoryInterviewRepository implements InterviewRepository {
    private final Map<Long, InterviewSession> sessions = new TreeMap<>();
    private final Map<Long, InterviewTurn> turns = new TreeMap<>();
    private long nextSession = 1;
    private long nextTurn = 1;

    public InterviewSession create(Topic topic, List<Question> questions) {
        long id = nextSession++;
        var session = new InterviewSession(id, topic, questions.size(), InterviewStatus.IN_PROGRESS, LocalDateTime.now(), null);
        sessions.put(id, session);
        for (int i = 0; i < questions.size(); i++) {
            var q = questions.get(i);
            long turnId = nextTurn++;
            turns.put(turnId, new InterviewTurn(turnId, id, i + 1, q.id(), q.title(), q.topic(), q.difficulty(),
                    q.referenceAnswer(), null, null, null, null));
        }
        return session;
    }

    public Optional<InterviewSession> find(long id) { return Optional.ofNullable(sessions.get(id)); }
    public Optional<InterviewSession> findForUpdate(long id) { return find(id); }
    public List<InterviewTurn> turns(long id) { return turns.values().stream().filter(t -> t.sessionId() == id).toList(); }
    public void saveAnswer(long id, String answer, String feedback, String mode) {
        var t = turns.get(id);
        turns.put(id, new InterviewTurn(t.id(), t.sessionId(), t.position(), t.questionId(), t.title(), t.topic(),
                t.difficulty(), t.referenceAnswer(), answer, feedback, mode, LocalDateTime.now()));
    }
    public void complete(long id) {
        var s = sessions.get(id);
        sessions.put(id, new InterviewSession(id, s.topic(), s.questionCount(), InterviewStatus.COMPLETED, s.createdAt(), LocalDateTime.now()));
    }
    public List<InterviewSession> findPage(int limit, long offset) {
        return sessions.values().stream().sorted(Comparator.comparingLong(InterviewSession::id).reversed()).skip(offset).limit(limit).toList();
    }
    public long count() { return sessions.size(); }
}
