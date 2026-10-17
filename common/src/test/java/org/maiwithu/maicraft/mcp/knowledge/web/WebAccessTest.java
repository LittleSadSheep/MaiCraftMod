// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** 查百科只走已适配的条目路径：恶意链接、站点禁止抓取的路径与 robots 禁行规则都不能进入网络层。 */
class WebAccessTest {
    @Test
    void articleUrlsStripFragmentsAndKeepTheEntryPath() {
        assertTrue(WebAccess.article("https://www.mcmod.cn/item/42.html#recipe").toString().endsWith("42.html"));
        assertTrue(WebAccess.article("https://minecraft.wiki/w/Stone").toString().endsWith("w/Stone"));
    }

    @Test
    void sitemapIndexPathsAreFetchableButApiPathsAreNot() throws Exception {
        WebAccess.requireFetch(URI.create("https://zh.minecraft.wiki/images/sitemaps/NS_0-0.xml"));
        assertThrows(WebKnowledgeException.class,
                () -> WebAccess.requireFetch(URI.create("https://minecraft.wiki/api.php?action=query")),
                "API 路径不在适配范围内");
    }

    @Test
    void urlsOutsideTheAdaptedRoutesAreRefused() {
        String[] refused = {
                "http://www.mcmod.cn/item/1.html",
                "https://www.mcmod.cn.evil.test/item/1.html",
                "https://user@www.mcmod.cn/item/1.html",
                "https://www.mcmod.cn:444/item/1.html",
                "https://127.0.0.1/item/1.html",
                "https://search.mcmod.cn/s?key=stone",
                "https://minecraft.wiki/w/Special:Search",
                "https://www.mcmod.cn/item/edit/1.html",
                "https://minecraft.wiki/w/%2e%2e/api.php",
                "https://minecraft.wiki/w/Stone?action=edit",
        };
        for (String url : refused) {
            assertThrows(IllegalArgumentException.class, () -> WebAccess.article(url), url);
        }
    }

    @Test
    void robotsRulesPickTheMostSpecificAgentGroup() {
        var generic = new RobotsRules("User-agent: *\nDisallow: /api.php\nAllow: /api.php?action=query$");
        assertFalse(generic.allows(URI.create("https://minecraft.wiki/api.php?action=parse")));
        assertTrue(generic.allows(URI.create("https://minecraft.wiki/api.php?action=query")));

        // 同一客户端的多组规则合并，较具体身份胜过通配组，同长度 Allow 优先。
        var specific = new RobotsRules("User-agent: *\nDisallow: /\nUser-agent: MaiCraftKnowledge\nDisallow: /secret*\n"
                + "Allow: /secret/open\nUser-agent: MaiCraftKnowledge\nDisallow: /closed\nCrawl-delay: 2.5");
        assertTrue(specific.allows(URI.create("https://minecraft.wiki/w/Stone")));
        assertFalse(specific.allows(URI.create("https://minecraft.wiki/secret123")));
        assertTrue(specific.allows(URI.create("https://minecraft.wiki/secret/open")));
        assertFalse(specific.allows(URI.create("https://minecraft.wiki/closed")));
        assertEquals(2500, specific.delayMillis());
    }

    @Test
    void robotsRulesNormalizePercentEncoding() {
        // 站点规则与请求都归一化百分号编码，中文条目不能因编码方式不同而绕开同一条限制。
        var encoded = new RobotsRules("User-agent: *\nDisallow: /w/石头\nDisallow: /w/%53tone\nDisallow: /x\nAllow: /x");
        assertFalse(encoded.allows(URI.create("https://minecraft.wiki/w/%E7%9F%B3%E5%A4%B4")));
        assertFalse(encoded.allows(URI.create("https://minecraft.wiki/w/Stone")));
        assertTrue(encoded.allows(URI.create("https://minecraft.wiki/x")));
    }
}
