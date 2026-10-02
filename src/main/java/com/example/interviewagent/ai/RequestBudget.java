package com.example.interviewagent.ai;

import java.time.Duration;

/** 所有真实 HTTP 出站请求都必须经过此边界，业务重试也不能绕过。 */
public interface RequestBudget {
    void acquire(String provider);
    void block(String provider, Duration delay);
}
