package com.example.interviewagent.agent;

import com.example.interviewagent.exception.InvalidRequestException;
import com.example.interviewagent.service.InterviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class AgentService {
    private final AgentRepository repository;
    private final AgentEngine engine;
    private final InterviewService interviews;
    public AgentService(AgentRepository repository, AgentEngine engine, InterviewService interviews) {
        this.repository = repository; this.engine = engine; this.interviews = interviews;
    }
    public AgentRepository.Run create(String requestId, String prompt, Long sessionId) {
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new InvalidRequestException("requestId 必须为 UUID");
        if (prompt == null || prompt.isBlank() || prompt.length() > 2000) throw new InvalidRequestException("复习目标需要 1～2000 字符");
        if (sessionId != null) interviews.get(sessionId); // 页面授权的上下文，模型不能通过工具参数改成其他记录。
        return repository.create(requestId.toLowerCase(Locale.ROOT), prompt.strip(), sessionId);
    }
    public List<AgentRepository.Run> list() { return repository.list(); }
    public AgentRepository.Detail get(long id) { return repository.detail(id); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentRepository.Detail execute(long id) {
        var claim = repository.claim(id);
        if (claim == null) return get(id); // 已结束的运行只返回存档；“重试”需要新 requestId，避免误扣额度。
        var outcome = engine.run(claim.run().prompt(), claim.run().sessionId(), new AgentEngine.Trace() {
            public int start(String kind, String name, String input) { return repository.startStep(claim, kind, name, input); }
            public void end(int step, String state, String output) { repository.endStep(claim, step, state, output); }
        });
        repository.finish(claim, outcome);
        return get(id);
    }
}
