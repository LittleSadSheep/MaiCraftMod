// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * 回执与契约文本中的能力指针防漂移：文本点名的能力必须仍在能力表里。
 * 能力改名或下线而文本未同步时，模型会拿到一个提交即被拒的指针；
 * 本回归在启动期就让这种漂移编译后即可见。
 */
public final class AbilityPointerDriftTest {
    /** 形如 maicraft:xxx 的能力全名；maicraft:// 知识 URI 因冒号后紧跟斜杠而天然不匹配。 */
    private static final Pattern ABILITY_TOKEN = Pattern.compile("\\bmaicraft:[a-z_]+\\b");
    /** find_block 指路话术中点名的裸能力名 → 对应能力全名。 */
    private static final Map<String, String> POINTER_NAMES = Map.of(
            "travel", "maicraft:travel",
            "harvest_block", "maicraft:harvest_block",
            "interact", "maicraft:interact");

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var known = IntentRuntime.KNOWN_ABILITIES;
        check("known abilities nonempty", !known.isEmpty());
        for (String ability : known) {
            JsonObjectish desc = new JsonObjectish(SemanticAbilityCatalog.describe(ability));
            for (Matcher m = ABILITY_TOKEN.matcher(desc.text()); m.find(); ) {
                String token = m.group();
                check("catalog text of " + ability + " names live ability " + token,
                        known.contains(token));
            }
        }
        String pointer = org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask.NEXT_STEP_POINTER;
        for (var entry : POINTER_NAMES.entrySet()) {
            check("find_block pointer names " + entry.getKey(), pointer.contains(entry.getKey()));
            check("pointed ability " + entry.getValue() + " is live", known.contains(entry.getValue()));
        }
        System.out.println("AbilityPointerDriftTest OK: "
                + known.size() + " contracts scanned, pointer names " + POINTER_NAMES.keySet() + " all live");
    }

    private static void check(String what, boolean condition) {
        if (!condition) throw new AssertionError("ability pointer drift: " + what);
    }

    /** 避免直接依赖 Gson 类型名；只做深度优先收集全部字符串值。 */
    private static final class JsonObjectish {
        private final String text;
        JsonObjectish(com.google.gson.JsonObject json) { this.text = flatten(json); }
        String text() { return text; }
        private static String flatten(com.google.gson.JsonElement element) {
            StringBuilder sb = new StringBuilder();
            collect(element, sb);
            return sb.toString();
        }
        private static void collect(com.google.gson.JsonElement element, StringBuilder sb) {
            if (element.isJsonObject()) {
                for (var member : element.getAsJsonObject().entrySet()) collect(member.getValue(), sb);
            } else if (element.isJsonArray()) {
                for (var item : element.getAsJsonArray()) collect(item, sb);
            } else if (element.isJsonPrimitive()) {
                sb.append(element.getAsString()).append('\n');
            }
        }
    }
}
