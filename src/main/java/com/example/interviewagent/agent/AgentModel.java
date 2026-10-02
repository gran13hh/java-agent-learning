package com.example.interviewagent.agent;

import org.springframework.ai.chat.messages.*;
import java.util.List;

public interface AgentModel {
    AssistantMessage next(List<Message> messages, boolean allowTools);
}
