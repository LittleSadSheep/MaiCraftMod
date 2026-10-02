// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** 用内存传输核对真实下载流程；缓存过期与恶意跳转不能让角色读到不受支持的来源。 */
public final class WebHttpClientTest {
    public static void main(String[] args) throws Exception {
        URI article = URI.create("https://www.mcmod.cn/item/42.html");
        var clock = new TestClock();
        var fake = new FakeClient(request -> reply(request, 200, Map.of(),
                request.uri().getPath().equals("/robots.txt") ? "User-agent: *\nDisallow: /item/edit/" : "<p>机器资料</p>"));
        try (var client = new WebHttpClient(fake, clock)) {
            var first = client.get(article, deadline());
            var second = client.get(article, deadline());
            check(fake.calls == 2 && !first.cached() && second.cached() && first.fetchedAt().equals(second.fetchedAt()), "reuse article with original timestamp");
            clock.now = clock.now.plus(Duration.ofMinutes(16));
            check(!client.get(article, deadline()).cached() && fake.calls == 4, "refresh both policy and article after expiry");
            expect(client, article, System.nanoTime() - 1, "query_timeout");
        }
        var denied = new FakeClient(request -> reply(request, 200, Map.of(), "User-agent: *\nDisallow: /"));
        try (var client = new WebHttpClient(denied, clock)) {
            expect(client, article, deadline(), "robots_disallowed"); check(denied.calls == 1, "policy denial prevents article request");
        }
        var challenge = new FakeClient(request -> reply(request, 200, Map.of(), "<html>Verify you are human</html>"));
        try (var client = new WebHttpClient(challenge, clock)) { expect(client, article, deadline(), "robots_unavailable"); }
        var redirect = new FakeClient(request -> request.uri().getPath().equals("/robots.txt")
                ? reply(request, 404, Map.of(), "") : reply(request, 302, Map.of("location", List.of("http://127.0.0.1/private")), ""));
        try (var client = new WebHttpClient(redirect, clock)) {
            expect(client, article, deadline(), "url_not_allowed"); check(redirect.calls == 2, "redirect target never fetched");
        }
        var limited = new FakeClient(request -> request.uri().getPath().equals("/robots.txt")
                ? reply(request, 200, Map.of(), "User-agent: *") : reply(request, 429, Map.of(), ""));
        try (var client = new WebHttpClient(limited, clock)) { expect(client, article, deadline(), "rate_limited"); }
        // 合法压缩索引完整还原；异常膨胀的文件明确拒绝，不能成为半份标题目录。
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) { gzip.write("<urlset/>".getBytes(StandardCharsets.UTF_8)); }
        check(new String(WebHttpClient.expandSitemap(compressed.toByteArray()), StandardCharsets.UTF_8).equals("<urlset/>"), "gzip sitemap");
        compressed.reset();
        try (var gzip = new GZIPOutputStream(compressed)) { for (int i = 0; i < 2049; i++) gzip.write(new byte[8192]); }
        try { WebHttpClient.expandSitemap(compressed.toByteArray()); throw new AssertionError("expanded size"); }
        catch (WebKnowledgeException expected) { check(expected.code().equals("response_too_large"), "expanded limit"); }
        System.out.println("WebHttpClientTest: passed");
    }

    private static void expect(WebHttpClient client, URI uri, long deadline, String code) throws Exception {
        try { client.get(uri, deadline); throw new AssertionError("expected " + code); }
        catch (WebKnowledgeException failure) { check(failure.code().equals(code), code + " != " + failure.code()); }
    }
    private static long deadline() { return System.nanoTime() + Duration.ofSeconds(10).toNanos(); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static Reply reply(HttpRequest request, int status, Map<String, List<String>> extra, String body) {
        var headers = new HashMap<>(extra);
        headers.put("content-type", List.of("text/html; charset=utf-8"));
        return new Reply(request, status, HttpHeaders.of(headers, (name, value) -> true), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private record Reply(HttpRequest request, int statusCode, HttpHeaders headers, byte[] body) implements HttpResponse<byte[]> {
        public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
    private static final class FakeClient extends HttpClient {
        final Function<HttpRequest, Reply> answer; int calls;
        FakeClient(Function<HttpRequest, Reply> answer) { this.answer = answer; }
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            calls++; return (HttpResponse<T>) answer.apply(request);
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public void shutdownNow() {}
    }
}
