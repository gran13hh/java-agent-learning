package com.example.interviewagent.controller;

import com.example.interviewagent.agent.AgentProgressStream;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AgentProgressController {
    private final AgentProgressStream progress;
    public AgentProgressController(AgentProgressStream progress) { this.progress = progress; }
    @GetMapping(value = "/api/agent/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no").body(progress.open(id));
    }
}
