package com.example.interviewagent.ai;

import com.example.interviewagent.exception.*;
import com.openai.core.RequestOptions;
import com.openai.core.http.*;
import okhttp3.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK 的同步 HTTP 适配器：复用已下载的 OkHttp，明确禁止自动重试与重定向。 */
public final class BudgetedHttpClient implements com.openai.core.http.HttpClient {
    private final OkHttpClient client;
    private final RequestBudget budget;
    private final String provider;
    public BudgetedHttpClient(RequestBudget budget, String provider) {
        this.budget = budget;
        this.provider = provider;
        client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(45))
                .addNetworkInterceptor(chain -> {
                    // 建连完成、真正发送 HTTP 之前计数，连接耗时不会提前消耗滑动窗口。
                    budget.acquire(provider);
                    chain.request().tag(AtomicBoolean.class).set(true);
                    return chain.proceed(chain.request());
                })
                .callTimeout(Duration.ofSeconds(45)).build();
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        var body = new ByteArrayOutputStream();
        if (request.body() != null) request.body().writeTo(body);
        var counted = new AtomicBoolean();
        var builder = new Request.Builder().url(request.url()).tag(AtomicBoolean.class, counted);
        for (var name : request.headers().names()) {
            for (var value : request.headers().values(name)) builder.addHeader(name, value);
        }
        builder.method(request.method().toString(), request.body() == null ? null : RequestBody.create(
                body.toByteArray(), MediaType.parse(request.body().contentType())));
        var call = client.newCall(builder.build());
        if (options.getTimeout() != null) {
            long nanos = Math.min(Duration.ofSeconds(45).toNanos(), options.getTimeout().request().toNanos());
            call.timeout().timeout(Math.max(1, nanos), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        // SDK 和连接层均没有自动重试；额度由 network interceptor 独立提交。
        try (var response = call.execute()) {
            if (response.code() == 429) {
                long seconds = retryAfter(response.header("Retry-After"));
                budget.block(provider, Duration.ofSeconds(seconds));
                throw new AiRateLimitException(seconds);
            }
            if (response.isRedirect()) throw new AiCallException("模型服务返回了重定向，请检查配置地址；未自动跟随");
            // 上游错误可能包含请求内容；不让 SDK 解析后把敏感内容写进异常或日志。
            if (!response.isSuccessful()) throw new AiCallException("模型服务返回 HTTP " + response.code() + "，请稍后重试或检查配置");
            byte[] bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new AiCallException("模型响应超过大小限制");
            var headers = com.openai.core.http.Headers.builder().putAll(response.headers().toMultimap()).build();
            int code = response.code();
            return new HttpResponse() {
                private final InputStream stream = new ByteArrayInputStream(bytes);
                public int statusCode() { return code; }
                public com.openai.core.http.Headers headers() { return headers; }
                public InputStream body() { return stream; }
                public void close() { /* 字节数组响应没有网络资源。 */ }
            };
        } catch (IOException exception) {
            // DNS/TLS 等建连前失败也保守地计入一次；已经计数的请求不会重复扣额度。
            if (!counted.get()) budget.acquire(provider);
            throw new AiCallException("模型请求连接失败或超过 45 秒；回答已保存，可稍后重试反馈");
        }
    }

    static long retryAfter(String value) {
        if (value != null) {
            try { return Math.min(86400, Math.max(1, Long.parseLong(value.trim()))); }
            catch (NumberFormatException ignored) {
                try {
                    var until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Math.min(86400, Math.max(1, Duration.between(Instant.now(), until).toSeconds() + 1));
                } catch (RuntimeException ignoredDate) { /* 没有有效建议时至少等待 60 秒。 */ }
            }
        }
        return 60;
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("当前适配器只支持同步请求"));
    }

    @Override
    public void close() {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
