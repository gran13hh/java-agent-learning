package com.example.interviewagent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 使用标准 ProblemDetail 错误结构：status、title、detail、instance。 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(QuestionNotFoundException.class)
    public ProblemDetail notFound(QuestionNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InterviewNotFoundException.class)
    public ProblemDetail interviewNotFound(InterviewNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InterviewConflictException.class)
    public ProblemDetail interviewConflict(InterviewConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail invalidRequest(InvalidRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "请求参数校验失败");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail databaseFailure(DataAccessException exception) {
        // 客户端只收到稳定错误；不返回 SQL、连接地址或驱动异常内容。
        LOG.error("数据库操作失败，异常类型：{}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "数据库操作暂时失败，请检查服务状态");
    }

    private record FieldError(String field, String message) {
    }
}
