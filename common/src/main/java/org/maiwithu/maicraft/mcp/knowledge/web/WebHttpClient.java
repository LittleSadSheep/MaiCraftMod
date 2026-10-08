// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;

/** 查百科先核对访问规则，再限流下载；所有等待都在资料工作线程，不占用游戏刻。 */
public final class WebHttpClient implements WebFetch, AutoCloseable {
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final int CACHE_CHARS = 4 * 1024 * 1024;
    private final HttpClient client;
    private final Clock clock;
    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();
    private final LinkedHashMap<URI, Page> cache = new LinkedHashMap<>(16, .75f, true);
    private int cachedChars;

    public WebHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    // 回放固定网页时注入时钟与传输，验证缓存过期和拒绝路径无需反复访问百科。
    WebHttpClient(HttpClient client, Clock clock) { this.client = client; this.clock = clock; }

    private static final class HostState {
        final ReentrantLock lock = new ReentrantLock();
        RobotsRules robots;
        Instant expires = Instant.EPOCH;
        long lastRequest;
    }

    @Override public Page get(URI original, long deadline) throws IOException, InterruptedException {
        URI uri = original;
        for (int redirects = 0; redirects <= 3; redirects++) {
            WebAccess.requireFetch(uri);
            remaining(deadline);
            synchronized (cache) {
                Page known = cache.get(uri);
                if (known != null && known.fetchedAt().plus(TTL).isAfter(clock.instant())) return known.fromCache();
            }
            HostState host = hosts.computeIfAbsent(uri.getHost(), ignored -> new HostState());
            if (!host.lock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) throw timeout();
            try {
                // 同一来源的查询串行通过限流门；不把站点拒绝或验证页面缓存成游戏知识。
                if (host.robots == null || host.expires.isBefore(clock.instant())) {
                    URI policy = URI.create("https://" + uri.getHost() + "/robots.txt");
                    var response = download(policy, host, deadline, 256 * 1024);
                    String text = new String(response.body(), StandardCharsets.UTF_8);
                    if (response.statusCode() == 404) text = "";
                    else if (response.statusCode() != 200 || text.stripLeading().startsWith("<"))
                        throw new WebKnowledgeException("robots_unavailable", "Cannot verify the site's robots policy (HTTP " + response.statusCode() + ")");
                    host.robots = new RobotsRules(text); host.expires = clock.instant().plus(TTL);
                }
                if (!host.robots.allows(uri)) throw new WebKnowledgeException("robots_disallowed", "The site disallows automated access to this route");
                boolean sitemap = WebAccess.sitemap(uri);
                var response = download(uri, host, deadline, (sitemap ? 8 : 2) * 1024 * 1024);
                int status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) throw new WebKnowledgeException("invalid_redirect", "Missing redirect destination");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) throw new WebKnowledgeException(status == 429 ? "rate_limited" : "http_error", "The encyclopedia returned HTTP " + status);
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                if (!(type.contains("text/html") || type.contains("text/plain") || sitemap &&
                        (type.contains("xml") || type.contains("gzip") || type.contains("octet-stream"))))
                    throw new WebKnowledgeException("unexpected_content", "The endpoint did not return a document");
                byte[] body = response.body();
                if (sitemap && uri.getPath().endsWith(".gz")) body = expandSitemap(body);
                Page page = new Page(uri, new String(body, StandardCharsets.UTF_8), clock.instant(),
                        response.headers().firstValue("Last-Modified").orElse(""), false);
                remember(uri, page);
                return page;
            } finally { host.lock.unlock(); }
        }
        throw new WebKnowledgeException("redirect_limit", "Too many encyclopedia redirects");
    }

    private HttpResponse<byte[]> download(URI uri, HostState host, long deadline, int maximum)
            throws IOException, InterruptedException {
        WebAccess.requireFetch(uri);
        long interval = TimeUnit.MILLISECONDS.toNanos(host.robots == null ? 1000 : host.robots.delayMillis());
        long pause = interval - (System.nanoTime() - host.lastRequest);
        if (host.lastRequest != 0 && pause > 0) {
            if (pause >= remaining(deadline)) throw timeout();
            TimeUnit.NANOSECONDS.sleep(pause);
        }
        host.lastRequest = System.nanoTime();
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(remaining(deadline), TimeUnit.SECONDS.toNanos(4))))
                .header("User-Agent", "MaiCraftKnowledge/0.1 (+https://github.com/LittleSadSheep/MaiCraftMod; on-demand reference)")
                .header("Accept", "application/json, text/html, text/plain")
                .header("Accept-Encoding", "identity").GET().build();
        // 使用有界订阅器读完响应；不返回仍可能无限等待的流，也不伪装浏览器突破验证。
        try { return client.send(request, info -> new BoundedDownload(maximum)); }
        catch (IOException failure) {
            // HTTP 客户端会包装正文订阅器的异常，仍把明确的超限原因交给模型，避免误判为条目不存在。
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof WebKnowledgeException known) throw known;
            throw failure;
        }
    }

    private synchronized void remember(URI uri, Page page) {
        synchronized (cache) {
            Page old = cache.put(uri, page);
            cachedChars += page.body().length() - (old == null ? 0 : old.body().length());
            while (cache.size() > 32 || cachedChars > CACHE_CHARS) {
                URI first = cache.keySet().iterator().next(); cachedChars -= cache.remove(first).body().length();
            }
        }
    }

    private static long remaining(long deadline) throws WebKnowledgeException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeout();
        return remaining;
    }

    private static WebKnowledgeException timeout() { return new WebKnowledgeException("query_timeout", "The shared encyclopedia query deadline was reached"); }

    // 英文站点地图以 gzip 发布；解压也有上限，不能因小压缩包把游戏进程内存耗尽。
    static byte[] expandSitemap(byte[] compressed) throws IOException {
        try (var stream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int maximum = 16 * 1024 * 1024;
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new WebKnowledgeException("response_too_large", "The expanded sitemap exceeds 16 MiB");
            return bytes;
        }
    }
    @Override public void close() { client.shutdownNow(); }
}
