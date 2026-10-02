// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.net.URI;

/** 查陌生机器时只读百科条目，恶意链接与站点禁止抓取的路径都不能进入网络层。 */
public final class WebAccessTest {
    public static void main(String[] args) throws Exception {
        check(WebAccess.article("https://www.mcmod.cn/item/42.html#recipe").toString().endsWith("42.html"));
        WebAccess.requireFetch(URI.create("https://zh.minecraft.wiki/api.php?action=query"));
        for (String url : new String[]{"http://www.mcmod.cn/item/1.html", "https://www.mcmod.cn.evil.test/item/1.html",
                "https://user@www.mcmod.cn/item/1.html", "https://www.mcmod.cn:444/item/1.html",
                "https://127.0.0.1/item/1.html", "https://search.mcmod.cn/s?key=stone",
                "https://minecraft.wiki/w/Special:Search", "https://www.mcmod.cn/item/edit/1.html",
                "https://minecraft.wiki/w/%2e%2e/api.php", "https://minecraft.wiki/w/Stone?action=edit"}) {
            try { WebAccess.article(url); throw new AssertionError(url); } catch (IllegalArgumentException expected) {}
        }
        var generic = new RobotsRules("User-agent: *\nDisallow: /api.php\nAllow: /api.php?action=query$");
        check(!generic.allows(URI.create("https://minecraft.wiki/api.php?action=parse")));
        check(generic.allows(URI.create("https://minecraft.wiki/api.php?action=query")));
        // 同一客户端的多组规则合并，较具体身份胜过通配组，同长度 Allow 优先。
        var specific = new RobotsRules("User-agent: *\nDisallow: /\nUser-agent: MaiCraftKnowledge\nDisallow: /secret*\n"
                + "Allow: /secret/open\nUser-agent: MaiCraftKnowledge\nDisallow: /closed\nCrawl-delay: 2.5");
        check(specific.allows(URI.create("https://minecraft.wiki/w/Stone")));
        check(!specific.allows(URI.create("https://minecraft.wiki/secret123")));
        check(specific.allows(URI.create("https://minecraft.wiki/secret/open")));
        check(!specific.allows(URI.create("https://minecraft.wiki/closed")) && specific.delayMillis() == 2500);
        var encoded = new RobotsRules("User-agent: *\nDisallow: /w/石头\nDisallow: /w/%53tone\nDisallow: /x\nAllow: /x");
        check(!encoded.allows(URI.create("https://minecraft.wiki/w/%E7%9F%B3%E5%A4%B4")));
        check(!encoded.allows(URI.create("https://minecraft.wiki/w/Stone")));
        check(encoded.allows(URI.create("https://minecraft.wiki/x")));
        System.out.println("WebAccessTest: passed");
    }

    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
}
