// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** 复现模型把区域与坐标混合的首版请求，同时保留命名区域和真实坐标目标的合法用法。 */
public final class PublicTargetContractTest {
    public static void main(String[] args) {
        for (String tool : List.of("plan", "execute")) {
            check(tool, "{\"kind\":\"area\",\"label\":\"产线\",\"position\":{\"x\":0,\"y\":65,\"z\":0}}", false);
            check(tool, "{\"kind\":\"area\",\"label\":\"产线\",\"position\":null}", true);
            check(tool, "{\"kind\":\"area\",\"label\":null}", false);
            check(tool, "{\"kind\":\"coordinates\",\"position\":{\"x\":0,\"y\":65,\"z\":0}}", true);
            check(tool, "{\"kind\":\"coordinates\",\"position\":null}", false);
            check(tool, "{\"kind\":\"prior_result\",\"relation\":\"刚建好的工厂\"}", true);
            check(tool, "{\"kind\":\"prior_result\"}", false);
            check(tool, "{\"kind\":\"current_place\"}", true);
            // 机器设计可以完全脱离场地，但带场地的调用必须先取得勘察编号。
            var generic = JsonParser.parseString("{\"goal\":{\"ability\":\"maicraft:design_machine\",\"outcome\":\"审阅布局\"}}").getAsJsonObject();
            PublicToolCatalog.validateAndNormalize(tool, generic);
            generic.getAsJsonObject("goal").add("target", JsonParser.parseString("{\"kind\":\"area\",\"label\":\"产线\"}"));
            try { PublicToolCatalog.validateAndNormalize(tool, generic); throw new AssertionError("site accepted without inspection"); }
            catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("site_binding")) throw expected; }
            var parameters = new JsonObject(); parameters.addProperty("snapshot_id", "observed-site"); generic.getAsJsonObject("goal").add("parameters", parameters);
            PublicToolCatalog.validateAndNormalize(tool, generic);
        }
        // 三个携带目标的公开入口都要发布条件，而非只在服务器拒绝时才透露规则。
        for (var raw : PublicToolCatalog.definitions()) {
            var schema = raw.getAsJsonObject().getAsJsonObject("inputSchema");
            if (schema.has("$defs") && schema.getAsJsonObject("$defs").getAsJsonObject("semanticTarget").getAsJsonArray("oneOf").size() != 4)
                throw new AssertionError("public target variants missing");
        }
    }
    private static void check(String tool, String target, boolean valid) {
        JsonObject goal = new JsonObject(); goal.addProperty("ability", "maicraft:inspect_machine");
        goal.addProperty("outcome", "观察产线"); goal.add("target", JsonParser.parseString(target));
        JsonObject request = new JsonObject(); request.add("goal", goal);
        try { PublicToolCatalog.validateAndNormalize(tool, request); if (!valid) throw new AssertionError("accepted " + target); }
        catch (IllegalArgumentException rejected) { if (valid) throw new AssertionError(target, rejected); }
    }
}
