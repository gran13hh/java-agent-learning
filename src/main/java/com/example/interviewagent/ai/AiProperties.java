package com.example.interviewagent.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("app.ai")
public class AiProperties {
    private String feedbackMode = "AI";
    private Endpoint chat = new Endpoint();
    private Endpoint embedding = new Endpoint();
    public String getFeedbackMode() { return feedbackMode; }
    public void setFeedbackMode(String value) { feedbackMode = value; }
    public Endpoint getChat() { return chat; }
    public void setChat(Endpoint value) { chat = value; }
    public Endpoint getEmbedding() { return embedding; }
    public void setEmbedding(Endpoint value) { embedding = value; }

    // 不用自动生成 toString 的 record，避免误打印包含 Key 的配置对象。
    public static class Endpoint {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int expectedDimensions = 4096;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public int getExpectedDimensions() { return expectedDimensions; }
        public void setExpectedDimensions(int value) { expectedDimensions = value; }
    }
}
