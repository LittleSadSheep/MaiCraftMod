// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonParser;

/** 用真实参数入口验证公开的配置范围，避免知识页承诺调速或过滤动作却漏掉必需字段。 */
public final class CreateConfigurationContractTest {
    public static void main(String[] args) {
        validate("{\"action\":\"create.speed\",\"value\":-256}", true);
        validate("{\"action\":\"create.speed\",\"value\":257}", false);
        validate("{\"action\":\"create.speed\",\"value\":1.5}", false);
        validate("{\"action\":\"create.filter\",\"side\":\"up\",\"item_id\":\"minecraft:iron_ingot\"}", true);
        validate("{\"action\":\"create.filter\",\"side\":\"up\",\"clear\":true}", true);
        validate("{\"action\":\"create.filter\",\"side\":\"up\"}", false);
        validate("{\"action\":\"create.filter\",\"item_id\":\"minecraft:iron_ingot\"}", false);
        if (CreateConfigurationContract.describe().getAsJsonObject("actions").getAsJsonObject("create.speed").getAsJsonObject("value").get("maximum").getAsInt() != 256)
            throw new AssertionError("published speed limit differs from native request validation");
    }
    private static void validate(String request, boolean valid) {
        try { CreateConfigurationContract.validate(JsonParser.parseString(request).getAsJsonObject()); if (!valid) throw new AssertionError("accepted " + request); }
        catch (RuntimeException rejected) { if (valid) throw new AssertionError(request, rejected); }
    }
}
