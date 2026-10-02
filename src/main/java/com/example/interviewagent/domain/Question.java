package com.example.interviewagent.domain;

import java.time.LocalDateTime;

/**
 * record 是适合承载数据的 Java 类型，自动生成构造器、访问器、equals/hashCode。
 * 本阶段查询响应包含参考答案，方便学习；后续面试出题接口会使用不含答案的独立 DTO。
 */
public record Question(long id, String title, Topic topic, Difficulty difficulty,
                       String referenceAnswer, LocalDateTime createdAt) {
}
