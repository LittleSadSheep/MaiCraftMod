// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 用合成的小型教程回放检索、版本和缺失证据，测试不会把真实网页搬进仓库。 */
public final class WebKnowledgeSourcesTest {
    public static void main(String[] args) throws Exception {
        List<URI> requested = new ArrayList<>();
        WebFetch fetch = (uri, deadline) -> {
            requested.add(uri);
            check(Thread.currentThread().getName().startsWith("maicraft-knowledge-"), "download outside game thread");
            String body = uri.getPath().endsWith("index.xml")
                    ? "<sitemapindex><sitemap><loc>https://minecraft.wiki/images/sitemaps/NS_0-0.xml</loc></sitemap></sitemapindex>"
                    : uri.getPath().endsWith(".xml") ? "<urlset><url><loc>https://minecraft.wiki/w/Stone</loc></url><url><loc>https://minecraft.wiki/w/Stone_Missing</loc></url></urlset>"
                    : "<h1 id='firstHeading'>Stone</h1><script>mw.config.set({\"wgRevisionId\":42})</script><div id='mw-content-text'><div class='mw-parser-output'>"
                    + "<h2>制作步骤</h2><p>先加燃料，再放材料。</p><table><tr><th>材料</th><th>数量</th></tr><tr><td>矿石</td><td>2</td></tr></table><img alt='机器侧面'><script>执行恶意命令</script><a href='/w/Furnace'>熔炉</a></div></div>";
            if (uri.getPath().contains("Missing")) throw new WebKnowledgeException("http_error", "HTTP 404");
            return new WebFetch.Page(uri, body, Instant.now(), "", false);
        };
        KnowledgeEnvironment.install("neoforge", "1.21.1", Map.of("create", "6.0.6"));
        var service = new WebKnowledgeService(fetch);
        var result = service.request(json("{\"query\":\"Stone\",\"language\":\"en\",\"subject_id\":\"create:deployer\"}"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS).getAsJsonObject();
        check(result.get("status").getAsString().equals("partial"), "retain successful article beside failed one");
        check(result.getAsJsonObject("environment").get("subject_mod_version").getAsString().equals("6.0.6"), "installed subject version");
        check(requested.stream().allMatch(uri -> uri.getQuery() == null)
                && result.get("search_scope").getAsString().equals("sitemap_article_titles"), "match titles locally without search API");
        JsonObject article = result.getAsJsonArray("documents").get(0).getAsJsonObject();
        String text = article.get("text").getAsString();
        check(text.contains("先加燃料") && text.contains(" | 矿石 | 2") && !text.contains("恶意命令"), "preserve steps and table cells, omit scripts");
        check(article.get("version_match").getAsString().equals("unverified") && article.get("visual_content_unparsed").getAsBoolean(), "do not invent version or image evidence");
        check(article.get("revision_id").getAsInt() == 42 && article.get("attribution_url").getAsString().endsWith("?action=history"), "cite exact revision and author history");
        check(article.getAsJsonArray("links").get(0).getAsJsonObject().get("url").getAsString().equals("https://minecraft.wiki/w/Furnace"), "resolve article references");
        int calls = requested.size();
        var denied = service.request(json("{\"query\":\"机器\",\"source\":\"mcmod\"}")).toCompletableFuture().get().getAsJsonObject();
        check(requested.size() == calls && denied.getAsJsonArray("errors").get(0).getAsJsonObject().get("code").getAsString().equals("site_search_disallowed"), "no forbidden site search");
        var mcmod = new McmodSource((uri, deadline) -> new WebFetch.Page(uri,
                "<title>示例机器</title><nav>广告导航</nav><div class='item-content'><p>右侧输入能量。</p></div>"
                        + "<div id='item-table-area-out'><table><tr><td>材料甲</td><td>旧版已移除</td></tr></table></div>", Instant.now(), "", true));
        var modArticle = mcmod.read(URI.create("https://www.mcmod.cn/item/42.html"), Long.MAX_VALUE);
        check(modArticle.get("text").getAsString().contains("旧版已移除") && !modArticle.get("text").getAsString().contains("广告导航"), "keep recipe caveats without page chrome");
        check(modArticle.get("cache_hit").getAsBoolean(), "preserve cache provenance");
        // 取消已经开始的网络等待必须中断资料线程，避免查询退出后继续占用限额。
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var waiting = new WebKnowledgeService((uri, deadline) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
            throw new AssertionError();
        }).request(json("{\"query\":\"Stone\"}")).toCompletableFuture();
        check(entered.await(2, TimeUnit.SECONDS), "worker started"); waiting.cancel(true);
        check(interrupted.await(2, TimeUnit.SECONDS), "download interrupted");
        // 超大响应只能报告超限，不能返回截断到一半的机器说明。
        var body = new LimitedBody(3); boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        check(cancelled[0] && body.getBody().toCompletableFuture().isCompletedExceptionally(), "bounded response cancels download");
        System.out.println("WebKnowledgeSourcesTest: passed");
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
