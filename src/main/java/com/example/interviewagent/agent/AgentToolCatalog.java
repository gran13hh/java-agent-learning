package com.example.interviewagent.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.List;

/** Schema 用于告诉模型怎么申请工具；执行前仍需 Java 校验，不能信任模型遵守 Schema。 */
public final class AgentToolCatalog {
    private AgentToolCatalog() {}
    public static final List<ToolCallback> TOOLS = List.of(
            definition("list_questions", "按主题读取本地最新题目，不含参考答案；用于挑选复习练习。",
                    """
                    {"type":"object","properties":{"topic":{"type":"string","enum":["JAVA","MYSQL","REDIS","AGENT"]},
                    "limit":{"type":"integer","minimum":1,"maximum":5}},"required":["topic","limit"],"additionalProperties":false}
                    """),
            definition("search_knowledge", "检索用户导入的学习资料，返回至多3个原文片段与来源；没有命中时不能编造。需要一次向量请求。",
                    """
                    {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":500}},
                    "required":["query"],"additionalProperties":false}
                    """),
            definition("read_interview", "读取用户在页面选定的面试中最近3份已提交回答及已有反馈摘要，不生成新反馈；未选择面试则返回空结果。不能指定其他面试编号。",
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));

    private static ToolCallback definition(String name, String description, String schema) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
            }
            public String call(String input) {
                // 2.0.1 的 ChatModel 不自动执行工具。此处也明确拒绝旁路，必须由 AgentEngine 调白名单。
                throw new IllegalStateException("工具只能由受控 Agent 循环执行");
            }
        };
    }
}
