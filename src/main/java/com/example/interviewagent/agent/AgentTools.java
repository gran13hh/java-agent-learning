package com.example.interviewagent.agent;

import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.knowledge.KnowledgeService;
import com.example.interviewagent.repository.QuestionRepository;
import com.example.interviewagent.service.InterviewService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Component
public class AgentTools implements AgentToolExecutor {
    private final QuestionRepository questions;
    private final KnowledgeService knowledge;
    private final InterviewService interviews;
    private final JsonMapper json = JsonMapper.builder().build();
    public AgentTools(QuestionRepository questions, KnowledgeService knowledge, InterviewService interviews) {
        this.questions = questions; this.knowledge = knowledge; this.interviews = interviews;
    }

    @Override public String execute(String name, String arguments, Long selectedSessionId) {
        JsonNode args;
        try { args = json.readTree(arguments); }
        catch (RuntimeException exception) { throw new ToolRejectedException("参数必须是合法 JSON 对象"); }
        if (args == null || !args.isObject()) throw new ToolRejectedException("参数必须是 JSON 对象");
        // 显式白名单，不通过反射、Bean 名称、SQL 或 URL 调用任意能力。
        Object result = switch (name) {
            case "list_questions" -> {
                fields(args, "topic", "limit");
                Topic topic;
                try { topic = Topic.valueOf(string(args, "topic", 10)); }
                catch (IllegalArgumentException exception) { throw new ToolRejectedException("topic 只能是 JAVA、MYSQL、REDIS、AGENT"); }
                var limit = args.get("limit");
                if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.asInt() < 1 || limit.asInt() > 5)
                    throw new ToolRejectedException("limit 必须是 1～5 的整数");
                yield questions.findPage(topic, limit.asInt(), 0).stream()
                        .map(q -> Map.of("id", q.id(), "title", q.title(), "topic", q.topic().name(), "difficulty", q.difficulty().name())).toList();
            }
            case "search_knowledge" -> { fields(args, "query"); yield knowledge.search(string(args, "query", 500)); }
            case "read_interview" -> {
                fields(args);
                if (selectedSessionId == null) yield Map.of("message", "用户没有选择面试，请不要推断用户薄弱项", "turns", List.of());
                var session = interviews.get(selectedSessionId);
                var answered = session.turns().stream().filter(t -> t.answeredAt() != null).toList();
                yield Map.of("sessionId", selectedSessionId, "answeredCount", session.answeredCount(),
                        "note", "只返回最近3题；回答截取1000字符，反馈截取1800字符，截断会标注。已有反馈是学习参考，不是权威评分。",
                        "turns", answered.stream().skip(Math.max(0, answered.size() - 3)).map(t -> Map.of(
                                "title", t.title(), "topic", t.topic().name(), "answer", excerpt(t.answer(), 1000),
                                "feedback", excerpt(t.feedback(), 1800), "feedbackMode", Objects.toString(t.feedbackMode(), ""))).toList());
            }
            default -> throw new ToolRejectedException("工具未开放，只能使用 list_questions、search_knowledge、read_interview");
        };
        String output = json.writeValueAsString(result);
        if (output.length() > 12000) throw new ToolRejectedException("工具结果过长，请缩小查询范围");
        return output;
    }

    private static String excerpt(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n[后续内容已截断]";
    }
    private static void fields(JsonNode args, String... names) {
        if (args.size() != names.length || Arrays.stream(names).anyMatch(n -> !args.has(n)))
            throw new ToolRejectedException("工具参数字段缺失或包含未允许的字段，请按 Schema 修正");
    }
    private static String string(JsonNode args, String name, int max) {
        var value = args.get(name);
        if (value == null || !value.isString() || value.asText().isBlank() || value.asText().length() > max)
            throw new ToolRejectedException(name + " 必须是非空字符串，且长度不超过 " + max);
        return value.asText().strip();
    }
}
