// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;

/** 仅维护者显式传 --live 时读少量实页；常规回归绝不联网，不用更换身份绕过访问失败。 */
public final class WebKnowledgeLiveProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--live")) throw new IllegalArgumentException("Explicit --live required");
        try (var http = new WebHttpClient()) {
            var service = new WebKnowledgeService(http);
            for (String input : new String[]{"https://www.mcmod.cn/item/77861.html", "https://www.mcmod.cn/class/3124.html",
                    "https://www.mcmod.cn/post/2318.html", "wiki:en", "wiki:zh"}) {
                JsonObject request = new JsonObject();
                if (input.startsWith("wiki:")) {
                    request.addProperty("query", input.endsWith("zh") ? "石头" : "Stone");
                    request.addProperty("language", input.substring(5)); request.addProperty("limit", 1);
                } else request.addProperty("url", input);
                var result = service.request(request).toCompletableFuture().get(25, TimeUnit.SECONDS).getAsJsonObject();
                // 实页只输出诊断与正文长度，避免把完整百科或无关链接复制到构建日志。
                for (var row : result.getAsJsonArray("documents")) {
                    JsonObject document = row.getAsJsonObject();
                    document.addProperty("text_length", document.remove("text").getAsString().length()); document.remove("links");
                }
                System.out.println(input + " " + result);
            }
        }
    }
}
