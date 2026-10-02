package com.example.interviewagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 包根目录下的入口：组件扫描会发现子包中的 Controller、Service 和 Repository。 */
@SpringBootApplication
public class InterviewAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentApplication.class, args);
    }
}
