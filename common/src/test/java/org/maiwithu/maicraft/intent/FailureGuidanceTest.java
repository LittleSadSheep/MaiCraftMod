// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.task.TaskResult;

/** 失败码指路：替代入口与知识引用必须是读得到的真实入口，ad-hoc 码与无把握的失败码保持沉默。 */
public final class FailureGuidanceTest {
    public static void main(String[] args) {
        // 每个引用的知识 URI 都必须能离线解析（内置文档从 classpath 加载）；解析不到即悬挂引用。
        var library = KnowledgeLibrary.offline();
        for (FailureType type : FailureType.values())
            for (String uri : type.knowledgeRefs()) {
                KnowledgeDocument document = library.read(uri);
                check(document != null && !document.text().isBlank(),
                        "knowledge ref resolves to real content: " + type + " -> " + uri);
            }
        // NO_PATH 的回执必须带"对账后可考虑"措辞的替代入口和塌方知识卡——砾石塌方的瞬时无路不再被读成终态。
        var goal = new Goal("maicraft:travel", "下到目标层", null, "{}", "{}", List.of(), List.of());
        var failed = TaskResult.fail("本次寻路没有找到到达目标的路线", Map.of("failure_type", "no_path"));
        var option = firstOption(RecoveryKnowledge.attach(goal, failed));
        check(option.get("alternatives").getAsString().contains("may_alter_terrain")
                && option.get("alternatives").getAsString().startsWith("After reconciling"),
                "alternatives are framed as post-reconciliation considerations");
        check(option.getAsJsonArray("knowledge").asList().stream().anyMatch(value ->
                        value.getAsJsonObject().get("resource_uri").getAsString().endsWith("gravity-blocks")),
                "no_path points at the gravity knowledge card");
        // 内部异常与 ad-hoc 码不指路：错误的建议比没有建议更糟。
        var internal = firstOption(RecoveryKnowledge.attach(goal,
                TaskResult.fail("内部异常", Map.of("failure_type", "internal"))));
        check(!internal.has("alternatives"), "internal failures carry no entry-point advice");
        var adhoc = firstOption(RecoveryKnowledge.attach(goal,
                TaskResult.fail("可选依赖不可用", Map.of("failure_type", "optional_dependency_unavailable"))));
        check(!adhoc.has("alternatives"), "ad-hoc failure codes stay silent instead of guessing an entry");
        // 重复挂载不得追加或改写既有建议。
        var once = RecoveryKnowledge.attach(goal, failed);
        check(RecoveryKnowledge.attach(goal, once).toJson().equals(once.toJson()),
                "repeated attach does not accumulate advice");
        System.out.println("FailureGuidanceTest: passed");
    }
    private static JsonObject firstOption(TaskResult result) {
        var json = JsonParser.parseString(result.toJson()).getAsJsonObject();
        return json.getAsJsonObject("data").getAsJsonArray("recovery_options").get(0).getAsJsonObject();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
