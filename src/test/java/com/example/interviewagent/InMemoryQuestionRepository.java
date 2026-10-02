package com.example.interviewagent;

import com.example.interviewagent.domain.Question;
import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.dto.CreateQuestionRequest;
import com.example.interviewagent.repository.QuestionRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** 只用于测试业务与 HTTP 契约，不声称能验证 MySQL 的 SQL、锁或事务。 */
final class InMemoryQuestionRepository implements QuestionRepository {
    private final Map<Long, Question> questions = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public Question insert(CreateQuestionRequest request) {
        Question question = new Question(nextId++, request.title(), request.topic(), request.difficulty(),
                request.referenceAnswer(), LocalDateTime.now());
        questions.put(question.id(), question);
        return question;
    }

    @Override
    public Optional<Question> findById(long id) {
        return Optional.ofNullable(questions.get(id));
    }

    private Stream<Question> filtered(Topic topic) {
        return questions.values().stream().filter(q -> topic == null || q.topic() == topic);
    }

    @Override
    public List<Question> findPage(Topic topic, int limit, long offset) {
        return filtered(topic).sorted(Comparator.comparingLong(Question::id).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long count(Topic topic) {
        return filtered(topic).count();
    }
}
