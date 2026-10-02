package com.example.interviewagent.controller;

import com.example.interviewagent.domain.Question;
import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.dto.CreateQuestionRequest;
import com.example.interviewagent.dto.PageResponse;
import com.example.interviewagent.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** Controller 只负责 HTTP 适配，不直接拼 SQL，也不承担数据库事务。 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {
    private final QuestionService service;

    public QuestionController(QuestionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Question> create(@Valid @RequestBody CreateQuestionRequest request) {
        Question question = service.create(request);
        // 新建资源使用 201，并返回可以读取它的 Location 地址。
        return ResponseEntity.created(URI.create("/api/questions/" + question.id())).body(question);
    }

    @GetMapping("/{id}")
    public Question get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping
    public PageResponse<Question> list(@RequestParam(required = false) Topic topic,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size) {
        return service.list(topic, page, size);
    }
}
