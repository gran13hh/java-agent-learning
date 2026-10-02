package com.example.interviewagent.service;

import com.example.interviewagent.ai.FeedbackEvaluator;
import com.example.interviewagent.dto.InterviewView;
import com.example.interviewagent.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiFeedbackService {
    private final FeedbackTransactions transactions;
    private final FeedbackEvaluator evaluator;
    private final InterviewService interviews;
    public AiFeedbackService(FeedbackTransactions transactions, FeedbackEvaluator evaluator, InterviewService interviews) {
        this.transactions = transactions; this.evaluator = evaluator; this.interviews = interviews;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InterviewView generate(long sessionId, long turnId) {
        var claim = transactions.claim(sessionId, turnId); // 短事务 1：认领；返回时已释放行锁。
        if (claim == null) return interviews.get(sessionId);
        String feedback;
        try {
            feedback = evaluator.evaluate(claim.turn()); // 这里没有数据库事务，也不占用事务连接。
        } catch (AiRateLimitException exception) {
            transactions.finish(claim, "请求已达限额，回答已保存。稍后点击重试反馈。", "RATE_LIMITED");
            throw exception;
        } catch (RuntimeException exception) {
            String message = exception instanceof AiCallException ? exception.getMessage() : "反馈暂时无法生成，请稍后重试";
            transactions.finish(claim, message, "FAILED");
            throw new AiCallException(message);
        }
        transactions.finish(claim, feedback, "AI"); // 短事务 2：有条件写回；失败不触发再次调用模型。
        return interviews.get(sessionId);
    }
}
