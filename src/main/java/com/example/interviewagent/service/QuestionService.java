package com.example.interviewagent.service;

import com.example.interviewagent.domain.Question;
import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.dto.CreateQuestionRequest;
import com.example.interviewagent.dto.PageResponse;
import com.example.interviewagent.exception.InvalidRequestException;
import com.example.interviewagent.exception.QuestionNotFoundException;
import com.example.interviewagent.repository.QuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private final QuestionRepository repository;

    // 构造器注入明确表达依赖，便于测试；Spring 对单构造器无需额外 @Autowired。
    public QuestionService(QuestionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Question create(CreateQuestionRequest request) {
        return repository.insert(new CreateQuestionRequest(request.title().strip(), request.topic(),
                request.difficulty(), request.referenceAnswer().strip()));
    }

    @Transactional(readOnly = true)
    public Question get(long id) {
        if (id < 1) {
            throw new InvalidRequestException("id 必须大于 0");
        }
        return repository.findById(id).orElseThrow(() -> new QuestionNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<Question> list(Topic topic, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new InvalidRequestException("page 必须大于等于 1，size 必须在 1 到 100 之间");
        }
        // 先转成 long 再相乘，避免很大的 page 导致 int 溢出和负数 OFFSET。
        long offset = ((long) page - 1) * size;
        return new PageResponse<>(repository.findPage(topic, size, offset), page, size, repository.count(topic));
    }
}
