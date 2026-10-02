package com.example.interviewagent.repository;

import com.example.interviewagent.domain.Question;
import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.dto.CreateQuestionRequest;
import java.util.List;
import java.util.Optional;

/** 业务层依赖接口：运行时注入 JDBC 实现，单元测试可以使用内存替身。 */
public interface QuestionRepository {
    Question insert(CreateQuestionRequest request);
    Optional<Question> findById(long id);
    List<Question> findPage(Topic topic, int limit, long offset);
    long count(Topic topic);
}
