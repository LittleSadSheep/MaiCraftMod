// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 角色查阅百科只使用已适配的 HTTPS 来源；重定向也必须重新经过同一边界。 */
public final class WebAccess {
    private static final Set<String> WIKIS = Set.of("minecraft.wiki", "zh.minecraft.wiki");

    private WebAccess() {}

    public static boolean wiki(URI uri) { return WIKIS.contains(host(uri)); }

    public static URI article(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid encyclopedia URL"); }
        if (!baseAllowed(uri) || uri.getRawQuery() != null || !articlePath(uri))
            throw new IllegalArgumentException("Use an HTTPS Minecraft Wiki /w/ article or MC百科 /item/, /class/, /post/ URL without query parameters");
        // 章节锚点不改变网页身份；模型复制章节链接时仍读取该条目的完整正文。
        return URI.create("https://" + host(uri) + uri.getRawPath());
    }

    public static void requireFetch(URI uri) throws WebKnowledgeException {
        if (!baseAllowed(uri) || uri.getRawFragment() != null
                || !(uri.getPath().equals("/robots.txt") && uri.getRawQuery() == null
                || wiki(uri) && uri.getPath().equals("/api.php")
                || uri.getRawQuery() == null && articlePath(uri)))
            throw new WebKnowledgeException("url_not_allowed", "The destination is outside the adapted encyclopedia routes");
    }

    private static boolean baseAllowed(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && (wiki(uri) || "www.mcmod.cn".equals(host(uri)));
    }

    private static boolean articlePath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.contains("..") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return false;
        if (wiki(uri)) return path.startsWith("/w/") && path.length() > 3 && !path.substring(3).contains(":");
        return path.matches("/(item|class|post)/[0-9]+\\.html");
    }

    private static String host(URI uri) { return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT); }
}
