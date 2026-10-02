package com.example.interviewagent.controller;

import com.example.interviewagent.agent.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/agent/runs")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service = service; }
    public record Create(@NotBlank String requestId, @NotBlank @Size(max = 2000) String prompt, @Positive Long sessionId) {}
    @PostMapping public AgentRepository.Run create(@Valid @RequestBody Create input) { return service.create(input.requestId(), input.prompt(), input.sessionId()); }
    @GetMapping public List<AgentRepository.Run> list() { return service.list(); }
    @GetMapping("/{id}") public AgentRepository.Detail get(@PathVariable long id) { return service.get(id); }
    @PostMapping("/{id}/execute") public ResponseEntity<AgentRepository.Detail> execute(@PathVariable long id) {
        var detail = service.execute(id);
        // 运行失败是这个资源的状态，HTTP 200 仍返回完整记录；限流建议通过 Header 与 run 字段提供。
        var response = ResponseEntity.ok();
        if (detail.run().retryAfterSeconds() > 0) response.header("Retry-After", Long.toString(detail.run().retryAfterSeconds()));
        return response.body(detail);
    }
}
