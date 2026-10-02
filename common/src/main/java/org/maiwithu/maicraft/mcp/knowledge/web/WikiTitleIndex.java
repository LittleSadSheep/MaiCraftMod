// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** 仅缓存条目标题与链接；关键词留在本机比较，不向禁止检索的站点发送搜索请求。 */
final class WikiTitleIndex {
    private record Index(List<URI> articles, Instant fetchedAt) {}
    private record Match(URI uri, int score) {}
    private final WebFetch fetch;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();

    WikiTitleIndex(WebFetch fetch) { this.fetch = fetch; }

    MinecraftWikiSource.Search search(String query, String language, int limit, long deadline) throws IOException, InterruptedException {
        String origin = language.equals("zh") ? "https://zh.minecraft.wiki" : "https://minecraft.wiki";
        Index index = indexes.get(origin);
        if (index == null || index.fetchedAt().plus(Duration.ofMinutes(15)).isBefore(Instant.now())) {
            index = load(origin, deadline); indexes.put(origin, index);
        }
        String wanted = normalize(query);
        List<Match> matches = new ArrayList<>();
        for (URI uri : index.articles()) {
            String title = normalize(uri.getPath().substring(3));
            int score = title.equals(wanted) ? 1000 : title.startsWith(wanted) ? 750 : title.contains(wanted) ? 500
                    : List.of(wanted.split("\\s+")).stream().allMatch(title::contains) ? 250 : 0;
            if (score > 0) matches.add(new Match(uri, score));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(match -> match.uri().toString()));
        return new MinecraftWikiSource.Search(matches.stream().limit(limit).map(Match::uri).toList(), matches.size(),
                index.articles().size(), index.fetchedAt().toString());
    }

    private Index load(String origin, long deadline) throws IOException, InterruptedException {
        var root = fetch.get(URI.create(origin + "/images/sitemaps/index.xml"), deadline);
        var document = Jsoup.parse(root.body(), origin, Parser.xmlParser());
        if (document.selectFirst("sitemapindex") == null) throw invalid();
        var articles = new LinkedHashSet<URI>();
        boolean mainNamespace = false;
        // 只连续读取主命名空间的全部分片；任何分片失败都不把残缺索引标为“已检索完”。
        for (var location : document.select("sitemap > loc")) {
            URI part = URI.create(location.text());
            if (!part.getPath().matches("/images/sitemaps/NS_0-[0-9]+\\.xml(\\.gz)?")) continue;
            if (!origin.equals("https://" + part.getHost())) throw invalid();
            WebAccess.requireFetch(part); mainNamespace = true;
            var page = fetch.get(part, deadline);
            var entries = Jsoup.parse(page.body(), origin, Parser.xmlParser());
            if (entries.selectFirst("urlset") == null) throw invalid();
            for (var entry : entries.select("url > loc")) {
                URI article;
                try { article = WebAccess.article(entry.text()); }
                catch (IllegalArgumentException unsupported) { continue; }
                if (origin.equals("https://" + article.getHost())) articles.add(article);
            }
        }
        if (!mainNamespace) throw invalid();
        return new Index(List.copyOf(articles), root.fetchedAt());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.replace('_', ' '), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }
    private static WebKnowledgeException invalid() { return new WebKnowledgeException("invalid_sitemap", "The complete Wiki article index could not be read"); }
}
