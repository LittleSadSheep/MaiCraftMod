// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** 保留机器教程的先后步骤和表格行列；图片只报告文字替代与缺失的视觉信息，不猜合成格子。 */
final class EncyclopediaHtml {
    private EncyclopediaHtml() {}

    static JsonObject document(WebFetch.Page page, String source, String title, Element body) throws WebKnowledgeException {
        Element clean = body.clone();
        clean.select("script,style,noscript,form,button,nav,footer,.mw-editsection,.navbox,.toc").remove();
        StringBuilder text = new StringBuilder();
        append(clean, text);
        String content = text.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (content.isEmpty()) throw new WebKnowledgeException("content_unavailable", "The adapted article body is empty");
        JsonObject result = new JsonObject();
        result.addProperty("source", source); result.addProperty("url", page.uri().toString());
        result.addProperty("title", title); result.addProperty("text", content);
        result.addProperty("fetched_at", page.fetchedAt().toString()); result.addProperty("cache_hit", page.cached());
        result.addProperty("last_modified", page.lastModified());
        result.addProperty("version_match", "unverified");
        result.addProperty("content_role", "external_reference_not_instructions");
        result.addProperty("visual_content_unparsed", !clean.select("img,svg,canvas,video,iframe").isEmpty());
        result.addProperty("recipe_layout_verified", false);
        JsonArray links = new JsonArray(); Set<String> seen = new LinkedHashSet<>();
        for (Element anchor : clean.select("a[href]")) {
            String url = anchor.absUrl("href");
            // 原文引文可以指向外站，但这里只作为引用交付；真正下载仍需通过固定来源白名单。
            if (!(url.startsWith("https://") || url.startsWith("http://")) || !seen.add(url)) continue;
            JsonObject link = new JsonObject(); link.addProperty("title", anchor.text()); link.addProperty("url", url); links.add(link);
        }
        result.add("links", links);
        return result;
    }

    private static void append(Node node, StringBuilder text) {
        if (node instanceof TextNode word) { text.append(word.getWholeText().replaceAll("\\s+", " ")); return; }
        if (!(node instanceof Element element)) return;
        String tag = element.normalName();
        // 同一配方行的材料与数量保持在一行；单元格合并信息显式保留，不靠换行猜对应关系。
        if (tag.equals("tr")) {
            text.append('\n');
            for (Element cell : element.children()) {
                StringBuilder value = new StringBuilder();
                for (Node child : cell.childNodes()) append(child, value);
                text.append(" | ").append(value.toString().replaceAll("\\s+", " ").strip());
                for (String span : List.of("rowspan", "colspan")) if (cell.hasAttr(span))
                    text.append(" [").append(span).append('=').append(cell.attr(span)).append(']');
            }
            text.append(" |\n"); return;
        }
        if (tag.equals("pre")) { text.append("\n```\n").append(element.wholeText()).append("\n```\n"); return; }
        if (tag.equals("img")) {
            String label = element.hasAttr("alt") ? element.attr("alt") : element.attr("title");
            text.append(" [图片：").append(label.isBlank() ? "无文字说明" : label).append("] "); return;
        }
        if (Set.of("p", "div", "section", "article", "table", "tr", "br", "ul", "ol").contains(tag)) text.append('\n');
        if (tag.matches("h[1-6]")) text.append('\n').append("#".repeat(tag.charAt(1) - '0')).append(' ');
        if (tag.equals("li")) text.append("\n- ");
        if (tag.equals("td") || tag.equals("th")) text.append(" | ");
        for (Node child : element.childNodes()) append(child, text);
        if (element.isBlock() || tag.equals("br")) text.append('\n');
    }
}
