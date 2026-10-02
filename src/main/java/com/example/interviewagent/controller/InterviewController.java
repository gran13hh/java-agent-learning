package com.example.interviewagent.controller;

import com.example.interviewagent.domain.InterviewSession;
import com.example.interviewagent.dto.*;
import com.example.interviewagent.service.InterviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService service;

    public InterviewController(InterviewService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InterviewView> create(@Valid @RequestBody CreateInterviewRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/interviews/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<InterviewSession> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public InterviewView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/answers")
    public InterviewView answer(@PathVariable long id, @Valid @RequestBody SubmitAnswerRequest request) {
        return service.answer(id, request);
    }
}
