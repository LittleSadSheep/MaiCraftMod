// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.IntentRuntime;

/**
 * 运行时级死亡自恢复授权键（auto_respawn / recover_after_death）在 sequence 上必须被入口放行：
 * 契约文字写明 "accepted in goal.parameters of any ability"，语义契约层早已合并名单，
 * 工具层曾单独维护 sequence 参数白名单把它拒在门外，长链死亡自恢复无法整体挂授权。
 */
public final class SequenceRuntimeAuthKeysTest {
    public static void main(String[] args) {
        for (String key : IntentRuntime.runtimeAuthorizationKeys()) {
            // 工具层入口不再把授权键当未声明字段拒绝；运行时按任务级 goal 读取，覆盖整个序列执行期。
            PublicToolCatalog.validateAndNormalize("execute", sequenceRequest(key));
        }
        // 白名单外的字段仍被拒绝，放行授权键不等于放开 sequence 参数表。
        var intruder = sequenceRequest("auto_respawn");
        intruder.getAsJsonObject("goal").getAsJsonObject("parameters").addProperty("portal_method", "obsidian");
        try {
            PublicToolCatalog.validateAndNormalize("execute", intruder);
            throw new AssertionError("sequence 参数白名单外的字段被接受");
        } catch (IllegalArgumentException expected) { }
        System.out.println("SequenceRuntimeAuthKeysTest: passed");
    }

    /** 最小 sequence：一个无参 sleep 子目标，授权键挂在 sequence 自己的 parameters 上。 */
    private static JsonObject sequenceRequest(String authorizationKey) {
        JsonObject child = new JsonObject();
        child.addProperty("ability", "maicraft:sleep");
        child.addProperty("outcome", "安全过夜");
        child.add("parameters", new JsonObject());
        JsonArray children = new JsonArray();
        children.add(child);
        JsonObject parameters = new JsonObject();
        parameters.addProperty(authorizationKey, true);
        JsonObject goal = new JsonObject();
        goal.addProperty("ability", "maicraft:sequence");
        goal.addProperty("outcome", "整段夜间流程挂死亡自恢复");
        goal.add("parameters", parameters);
        goal.add("children", children);
        JsonObject request = new JsonObject();
        request.add("goal", goal);
        return request;
    }
}
