package com.example.interviewagent;

import com.example.interviewagent.ai.*;
import com.example.interviewagent.exception.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 使用 JDK 自带的本地 HTTP 服务，不访问公益站、不消耗真实额度。 */
class ModelGatewayTest {
    private HttpServer server;
    private ModelGateway gateway;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, Integer> permits = new HashMap<>();
    private int status = 200;
    private String body;
    private String lastPath;
    private String lastRequest;
    private String lastAuthorization;
    private Duration cooldown;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastPath = exchange.getRequestURI().getPath();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status == 429) exchange.getResponseHeaders().add("Retry-After", "17");
            if (status == 307) exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var properties = new AiProperties();
        for (var endpoint : List.of(properties.getChat(), properties.getEmbedding())) {
            endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            endpoint.setApiKey("test-key-never-real"); endpoint.setModel("custom/model"); endpoint.setExpectedDimensions(3);
        }
        gateway = new ModelGateway(properties, new RequestBudget() {
            public void acquire(String provider) {
                int count = permits.getOrDefault(provider, 0);
                if (count >= 5) throw new AiRateLimitException(60);
                permits.put(provider, count + 1);
            }
            public void block(String provider, Duration delay) { cooldown = delay; }
        });
    }

    @AfterEach
    void close() { if (gateway != null) gateway.close(); if (server != null) server.stop(0); }

    private String chatResponse(String text) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("id", "test", "object", "chat.completion",
                "created", 1, "model", "custom/model", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", text)))));
    }

    @Test
    void chatPreservesModelAndVersionPrefixAndSixthRequestDoesNotLeaveProcess() {
        body = chatResponse("有效反馈");
        for (int i = 0; i < 5; i++) assertEquals("有效反馈", gateway.chat("system", "user"));
        assertEquals("/v1/chat/completions", lastPath);
        assertEquals("Bearer test-key-never-real", lastAuthorization);
        assertTrue(lastRequest.contains("custom/model"));
        assertThrows(AiRateLimitException.class, () -> gateway.chat("system", "user"));
        assertEquals(5, requests.get());
    }

    @Test
    void failedRequestCountsAndDoesNotRetryOrLeakUpstreamBody() {
        status = 500; body = "{\"error\":\"upstream-secret-test-key-never-real\"}";
        var error = assertThrows(AiCallException.class, () -> gateway.chat("system", "user"));
        assertFalse(error.getMessage().contains("upstream-secret"));
        assertEquals(1, requests.get()); assertEquals(1, permits.get("chat"));
    }

    @Test
    void upstream429HonorsRetryAfterWithoutAutomaticRetry() {
        status = 429; body = "{}";
        var error = assertThrows(AiRateLimitException.class, () -> gateway.chat("system", "user"));
        assertEquals(17, error.retryAfterSeconds()); assertEquals(Duration.ofSeconds(17), cooldown);
        assertEquals(1, requests.get());
    }

    @Test
    void redirectIsNotFollowed() {
        status = 307; body = "{}";
        assertThrows(AiCallException.class, () -> gateway.chat("system", "user"));
        assertEquals(1, requests.get());
    }

    @Test
    void embeddingsUseTheirOwnBudgetAndValidateDimensions() {
        permits.put("chat", 5);
        body = """
                {"object":"list","model":"embedding-alias","data":[
                {"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],"usage":{"prompt_tokens":1,"total_tokens":1}}
                """;
        assertEquals(3, gateway.embed(List.of("text")).getFirst().length);
        assertEquals("/v1/embeddings", lastPath); assertEquals(1, permits.get("embedding"));
        assertTrue(lastRequest.contains("float"));
        body = body.replace("0.1,0.2,0.3", "0.1,0.2");
        assertThrows(AiCallException.class, () -> gateway.embed(List.of("text")));
    }

    @Test
    void invalidModelOutputCannotBecomeSuccessfulFeedback() {
        var evaluator = new AiFeedbackEvaluator(gateway, null);
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat("不是 JSON"));
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat("{}"));
        String valid = "{\"assessment\":\"基本准确\",\"strengths\":[\"概念清晰\"],\"improvements\":[\"补充实例\"],\"followUpQuestion\":\"你的项目中如何应用？\"}";
        assertTrue(evaluator.validateAndFormat(valid).contains("你的项目中如何应用？"));
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat(valid.replace("基本准确", "x".repeat(1001))));
    }
}
