package com.example.interviewagent.controller;

import com.example.interviewagent.ai.*;
import com.example.interviewagent.dto.InterviewView;
import com.example.interviewagent.service.AiFeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class AiController {
    private final AiFeedbackService feedback;
    private final ModelGateway gateway;
    private final AiProperties properties;
    public AiController(AiFeedbackService feedback, ModelGateway gateway, AiProperties properties) {
        this.feedback = feedback; this.gateway = gateway; this.properties = properties;
    }

    @PostMapping("/api/interviews/{sessionId}/turns/{turnId}/feedback")
    public InterviewView generate(@PathVariable long sessionId, @PathVariable long turnId) {
        return feedback.generate(sessionId, turnId);
    }

    @GetMapping("/api/ai/status")
    public Status status() { return new Status(properties.getFeedbackMode(), JdbcRequestBudget.LIMIT, JdbcRequestBudget.LIMIT, 60); }
    public record Status(String feedbackMode, int chatRequestsPerMinute, int embeddingRequestsPerMinute, int windowSeconds) {}

    /** 供本地自检使用；返回维度，不把大段向量塞进 UI。后续 RAG 直接复用 gateway.embed。 */
    @PostMapping("/api/ai/embeddings/check")
    public EmbeddingCheck check(@Valid @RequestBody EmbeddingInput input) {
        var vectors = gateway.embed(input.texts());
        return new EmbeddingCheck(vectors.size(), vectors.getFirst().length);
    }
    public record EmbeddingInput(@NotNull @Size(min = 1, max = 16) List<@NotBlank @Size(max = 4000) String> texts) {}
    public record EmbeddingCheck(int count, int dimensions) {}
}
