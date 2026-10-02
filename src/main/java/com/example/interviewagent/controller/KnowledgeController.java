package com.example.interviewagent.controller;

import com.example.interviewagent.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service) { this.service = service; }
    public record ImportRequest(@NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 6000) String content) {}
    public record SearchRequest(@NotBlank @Size(max = 1000) String query) {}
    @GetMapping("/documents")
    public List<KnowledgeService.Summary> list() { return service.list(); }
    @PostMapping("/documents")
    public KnowledgeService.Summary save(@Valid @RequestBody ImportRequest request) { return service.save(request.title(), request.content()); }
    @GetMapping("/documents/{id}")
    public KnowledgeService.Detail get(@PathVariable long id) { return service.get(id); }
    @PostMapping("/documents/{id}/index")
    public KnowledgeService.Summary index(@PathVariable long id) { return service.index(id); }
    @PostMapping("/search")
    public KnowledgeService.SearchResult search(@Valid @RequestBody SearchRequest request) { return service.search(request.query()); }
}
