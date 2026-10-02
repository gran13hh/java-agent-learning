package com.example.interviewagent.ai;

import com.example.interviewagent.exception.*;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** 聊天与向量分开配置、分开额度；后续 RAG 也必须经这个入口访问向量站点。 */
@Component
public class ModelGateway {
    private final AiProperties properties;
    private final RequestBudget budget;
    private final List<OpenAIClient> clients = new ArrayList<>();
    private OpenAiChatModel chat;
    private OpenAiEmbeddingModel embedding;
    public ModelGateway(AiProperties properties, RequestBudget budget) { this.properties = properties; this.budget = budget; }

    private OpenAIClient client(AiProperties.Endpoint endpoint, String provider) {
        if (endpoint.getBaseUrl() == null || endpoint.getApiKey() == null || endpoint.getApiKey().isBlank()
                || endpoint.getModel() == null || endpoint.getModel().isBlank()) {
            throw new AiCallException("请先填写本地 " + provider + " 模型配置");
        }
        URI uri = URI.create(endpoint.getBaseUrl());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))) {
            throw new AiCallException("模型地址格式不正确，请使用 HTTPS Base URL");
        }
        var client = new OpenAIClientImpl(ClientOptions.builder()
                .baseUrl(endpoint.getBaseUrl().replaceAll("/+$", ""))
                .apiKey(endpoint.getApiKey()).maxRetries(0).timeout(Duration.ofSeconds(45))
                .logLevel(LogLevel.OFF).httpClient(new BudgetedHttpClient(budget, provider)).build());
        clients.add(client);
        return client;
    }

    private synchronized OpenAiChatModel chat() {
        if (chat == null) {
            var endpoint = properties.getChat();
            var sdk = client(endpoint, "chat");
            chat = OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(sdk.async())
                    .options(OpenAiChatOptions.builder().model(endpoint.getModel()).maxRetries(0)
                            .timeout(Duration.ofSeconds(45)).maxCompletionTokens(1600).build()).build();
        }
        return chat;
    }

    private synchronized OpenAiEmbeddingModel embedding() {
        if (embedding == null) {
            var endpoint = properties.getEmbedding();
            embedding = OpenAiEmbeddingModel.builder().openAiClient(client(endpoint, "embedding"))
                    .options(OpenAiEmbeddingOptions.builder().model(endpoint.getModel()).maxRetries(0)
                            .timeout(Duration.ofSeconds(45)).encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT).build()).build();
        }
        return embedding;
    }

    public String chat(String system, String user) {
        try {
            var result = chat().call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            if (result == null || result.getResult() == null || result.getResult().getOutput() == null) {
                throw new AiCallException("模型没有返回有效反馈");
            }
            String text = result.getResult().getOutput().getText();
            if (text == null || text.isBlank() || text.length() > 20000) throw new AiCallException("模型反馈为空或过长");
            return text;
        } catch (RuntimeException exception) { throw sanitized(exception); }
    }

    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty() || texts.size() > 16 || texts.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 4000)) {
            throw new InvalidRequestException("向量输入为 1～16 段文本，每段 1～4000 字符");
        }
        try {
            var vectors = embedding().embed(texts);
            if (vectors.size() != texts.size()) throw new AiCallException("向量返回数量不匹配");
            for (float[] vector : vectors) {
                if (vector.length != properties.getEmbedding().getExpectedDimensions()) throw new AiCallException("向量维度与配置不匹配");
                boolean nonzero = false;
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw new AiCallException("向量包含无效数值");
                    nonzero |= value != 0;
                }
                if (!nonzero) throw new AiCallException("返回了无效的全零向量");
            }
            return vectors;
        } catch (RuntimeException exception) { throw sanitized(exception); }
    }

    private RuntimeException sanitized(RuntimeException exception) {
        // SDK 可能包装传输异常；只透传我们自己的安全消息，绝不输出原始响应/Key。
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiRateLimitException limited) return limited;
            if (cause instanceof AiCallException safe) return safe;
        }
        return new AiCallException("模型调用或响应解析失败，请检查服务状态后重试");
    }

    @PreDestroy
    public synchronized void close() { clients.forEach(OpenAIClient::close); }
}
