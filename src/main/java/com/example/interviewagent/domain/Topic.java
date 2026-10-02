package com.example.interviewagent.domain;

/** 枚举限制合法主题，避免数据库中出现 Java、java、JAVA 等不一致的取值。 */
public enum Topic {
    JAVA, MYSQL, REDIS, AGENT
}
