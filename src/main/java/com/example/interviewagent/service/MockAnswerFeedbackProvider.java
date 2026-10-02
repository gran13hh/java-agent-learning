package com.example.interviewagent.service;

import com.example.interviewagent.domain.InterviewTurn;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 规则只给表达建议，不判断内容正确性，不生成分数，不调用任何模型或网络。 */
@Component
@ConditionalOnProperty(name = "app.ai.feedback-mode", havingValue = "MOCK")
public class MockAnswerFeedbackProvider implements AnswerFeedbackProvider {
    @Override
    public Feedback evaluate(InterviewTurn question, String answer) {
        int length = answer.codePointCount(0, answer.length());
        String suggestion = length < 80
                ? "回答较简短，可以尝试补充：核心概念、为什么这样设计，以及一个具体例子。"
                : "已经形成一段完整表达。请对照参考答案，检查关键概念、适用条件和例子是否准确。";
        return new Feedback("MOCK", "Mock 规则反馈（未调用 AI，不判断对错）：本次回答约 " + length
                + " 个字符。\n" + suggestion + "\n复盘时可思考：这个知识点在你的项目里出现在哪里？");
    }
}
