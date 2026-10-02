package com.example.interviewagent.agent;

/** 可让模型在剩余轮数内纠正的工具参数错误；消息必须是本地固定文案。 */
public class ToolRejectedException extends RuntimeException {
    public ToolRejectedException(String message) { super(message); }
}
