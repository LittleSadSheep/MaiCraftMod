// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;
import org.maiwithu.maicraft.core.tools.SemanticParameters;

/** 精确指定一次原生任务书动作；任务、奖励和选择项不能互相替代，长编号始终按十六进制字符串传递。 */
public record FtbQuestActionRequest(String operation, String questId, String subjectId, String choiceUri) {
    public static final String RESOURCE = "maicraft://knowledge/ftbquests/quest/";

    public static FtbQuestActionRequest parse(JsonObject args) {
        // 每次只处理一个明确按钮；省略另一种对象编号，不能用 null、false 或零代替“本次不领奖/不提交”。
        // 任务书按钮参数写错名字时连同合法键报出，模型改正后重新提交同一个按钮动作。
        Set<String> questFields = Set.of("operation", "quest_id", "task_id", "reward_id", "choice_uri");
        for (String key : args.keySet()) if (!questFields.contains(key))
            throw new IllegalArgumentException("Unknown quest action parameter: " + key + SemanticParameters.acceptedKeys(questFields));
        String operation = string(args, "operation", true);
        if (!Set.of("submit", "confirm", "claim").contains(operation)) throw new IllegalArgumentException("operation must be submit, confirm or claim");
        String quest = id(string(args, "quest_id", true)); boolean claim = operation.equals("claim");
        String subject = id(string(args, claim ? "reward_id" : "task_id", true));
        if (args.has(claim ? "task_id" : "reward_id")) throw new IllegalArgumentException("Task and reward IDs cannot be mixed");
        String choice = string(args, "choice_uri", false);
        if (choice != null) {
            // 只能选择这个根奖励的直接候选；嵌套奖池的子节点并不是玩家可以独立领取的奖励。
            String prefix = RESOURCE + quest + "/rewards/" + subject + "/";
            if (!claim || !choice.startsWith(prefix) || !choice.substring(prefix.length()).matches("(0|[1-9][0-9]{0,8})~[0-9a-f]{16}"))
                throw new IllegalArgumentException("choice_uri must be an exact direct option URI returned for this quest reward");
        }
        return new FtbQuestActionRequest(operation, quest, subject, choice);
    }
    public JsonObject json() {
        JsonObject out = new JsonObject(); out.addProperty("operation", operation); out.addProperty("quest_id", questId);
        out.addProperty(operation.equals("claim") ? "reward_id" : "task_id", subjectId);
        if (choiceUri != null) out.addProperty("choice_uri", choiceUri); return out;
    }
    static long number(String id) { return Long.parseUnsignedLong(id, 16); }
    private static String id(String value) {
        // FTB 的对象编号可能占满 64 位；只统一十六进制字母大小写，不裁剪空格或接受 JSON 数字近似值。
        if (!value.matches("[0-9a-fA-F]{16}") || value.equals("0000000000000000")) throw new IllegalArgumentException("FTB ID must be a nonzero 16-digit hexadecimal string");
        return value.toUpperCase(Locale.ROOT);
    }
    private static String string(JsonObject args, String key, boolean required) {
        // choice_uri 可以完全不出现；显式 null 仍是错误类型，不能悄悄变成默认选项或普通领奖。
        if (!args.has(key)) { if (!required) return null; throw new IllegalArgumentException(key + " is required"); }
        if (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key + " must be a string");
        return args.get(key).getAsString();
    }
}
