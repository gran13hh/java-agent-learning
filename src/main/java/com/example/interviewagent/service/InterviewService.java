package com.example.interviewagent.service;

import com.example.interviewagent.domain.*;
import com.example.interviewagent.dto.*;
import com.example.interviewagent.exception.*;
import com.example.interviewagent.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewService {
    private final InterviewRepository interviews;
    private final QuestionRepository questions;
    private final AnswerFeedbackProvider feedbackProvider;

    public InterviewService(InterviewRepository interviews, QuestionRepository questions, AnswerFeedbackProvider feedbackProvider) {
        this.interviews = interviews;
        this.questions = questions;
        this.feedbackProvider = feedbackProvider;
    }

    @Transactional
    public InterviewView create(CreateInterviewRequest request) {
        if (request.questionCount() == null || request.questionCount() < 1 || request.questionCount() > 10) {
            throw new InvalidRequestException("题数必须为 1～10");
        }
        // V1 采用确定性的“最新 N 题”，方便理解和复现；不是随机抽题或模型选题。
        var selected = questions.findPage(request.topic(), request.questionCount(), 0);
        if (selected.size() < request.questionCount()) {
            throw new InvalidRequestException("该主题只有 " + selected.size() + " 道题，请减少题数或先补充题库");
        }
        var session = interviews.create(request.topic(), selected);
        return view(session);
    }

    @Transactional(readOnly = true)
    public InterviewView get(long id) {
        checkId(id);
        return view(interviews.find(id).orElseThrow(() -> new InterviewNotFoundException(id)));
    }

    @Transactional
    public InterviewView answer(long id, SubmitAnswerRequest request) {
        checkId(id);
        if (request.turnId() < 1 || request.answer() == null || request.answer().isBlank() || request.answer().length() > 10000) {
            throw new InvalidRequestException("请提供有效题目编号和 1～10000 字的回答");
        }
        // 锁定父会话后再读取题目；同一会话的所有写操作遵循相同锁顺序。
        var session = interviews.findForUpdate(id).orElseThrow(() -> new InterviewNotFoundException(id));
        var turns = interviews.turns(id);
        var turn = turns.stream().filter(item -> item.id() == request.turnId()).findFirst()
                .orElseThrow(() -> new InvalidRequestException("该题不属于本场面试"));
        String answer = request.answer().strip();
        // 网络超时重试可能发生在服务端已提交之后。相同回答直接返回，绝不再次生成反馈。
        if (turn.answered()) {
            if (!turn.answer().equals(answer)) throw new InterviewConflictException("本题已提交其他回答，请刷新查看记录");
            return InterviewView.from(session, turns);
        }
        var current = turns.stream().filter(item -> !item.answered()).findFirst().orElseThrow();
        if (session.status() != InterviewStatus.IN_PROGRESS || current.id() != turn.id()) {
            throw new InterviewConflictException("请按顺序回答当前题目");
        }
        // 当前仅执行本地 Mock，耗时很短。真实模型调用不能直接放到这里长时间占用锁。
        var feedback = feedbackProvider.evaluate(turn, answer);
        interviews.saveAnswer(turn.id(), answer, feedback.text(), feedback.mode());
        if (turn.position() == session.questionCount()) interviews.complete(id);
        return view(interviews.find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public PageResponse<InterviewSession> list(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new InvalidRequestException("page 至少为 1，size 为 1～100");
        return new PageResponse<>(interviews.findPage(size, (long) (page - 1) * size), page, size, interviews.count());
    }

    private InterviewView view(InterviewSession session) {
        return InterviewView.from(session, interviews.turns(session.id()));
    }

    private void checkId(long id) {
        if (id < 1) throw new InvalidRequestException("面试编号必须为正数");
    }
}
