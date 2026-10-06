// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;

import java.util.ArrayList;
import java.util.List;

/** 公开入口先按声明类型还原编码，再走原有严格检查；还原记录随请求返回，签名能直接指导改正。 */
public final class PublicArgumentNormalizationTest {
    public static void main(String[] args) {
        // 坐标目标的字符串整数在入口被还原，原先的 "x must be an integer" 不再出现。
        List<String> notes = new ArrayList<>();
        JsonObject execute = PublicToolCatalog.validateAndNormalize(PublicToolCatalog.EXECUTE, JsonParser.parseString("""
                {"goal":{"ability":"maicraft:interact","outcome":"右键拉杆",
                 "target":{"kind":"coordinates","position":{"x":"-86","y":"106","z":"28"}},
                 "parameters":{"block_id":"minecraft:lever"}}}"""), notes);
        JsonObject position = execute.getAsJsonObject("goal").getAsJsonObject("target").getAsJsonObject("position");
        check(position.get("x").getAsJsonPrimitive().isNumber() && position.get("x").getAsInt() == -86, "x restored");
        check(notes.size() == 3, "three coordinate restorations reported: " + notes);
        // 参数被写在 goal 顶层时搬进 parameters，原先的 "unexpected field: count" 不再出现。
        List<String> moved = new ArrayList<>();
        JsonObject acquire = PublicToolCatalog.validateAndNormalize(PublicToolCatalog.EXECUTE, JsonParser.parseString("""
                {"goal":{"ability":"maicraft:acquire_items","outcome":"取四个铁锭","count":"4",
                 "parameters":{"item_id":"minecraft:iron_ingot"}}}"""), moved);
        check(acquire.getAsJsonObject("goal").getAsJsonObject("parameters").get("count").getAsInt() == 4,
                "goal-level count moved into parameters");
        // 应答里改写的新目标也按同一规则还原。
        List<String> answered = new ArrayList<>();
        JsonObject task = PublicToolCatalog.validateAndNormalize(PublicToolCatalog.TASK, JsonParser.parseString("""
                {"action":"answer","task_id":"5928d8c4-c512-4f3c-8e18-4aaf800181b8",
                 "answer":{"decision_id":"d7aca651-d982-4ee2-a150-dfd081fb92e3","choice":"replace_goal",
                 "details":{"goal":{"ability":"maicraft:operate_machine","outcome":"取铁板","target":{"kind":"nearest"},
                 "parameters":{"operation":"ae2_supply","item_id":"minecraft:iron_ingot","allow_use":"true"}}}}}"""), answered);
        check(task.getAsJsonObject("answer").getAsJsonObject("details").getAsJsonObject("goal")
                .getAsJsonObject("parameters").get("allow_use").getAsBoolean(), "answer goal boolean restored");
        // 顶层字段有逐项类型的输入 Schema，仍保持严格：字符串半径照旧拒收。
        boolean strict = false;
        try {
            PublicToolCatalog.validateAndNormalize(PublicToolCatalog.PERCEIVE, JsonParser.parseString("""
                    {"view":"construction_site","label":"蜂房机器","radius":"8"}"""), new ArrayList<>());
        } catch (IllegalArgumentException expected) { strict = true; }
        check(strict, "top-level typed fields stay strict");
        // 拒收错误附带的签名只列字段名、类型和地点种类，足以改正而不必再读整份契约。
        JsonObject signature = SemanticAbilityCatalog.signature("maicraft:operate_machine");
        check("integer".equals(signature.getAsJsonObject("parameters").get("count").getAsString()), "signature lists count type");
        check(signature.getAsJsonArray("accepted_target_kinds").size() > 0, "signature lists target kinds");
        check(signature.toString().length() < 2000, "signature stays compact: " + signature.toString().length());
        System.out.println("PublicArgumentNormalizationTest OK: public entry restores host encodings before validation");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("public argument normalization: " + what);
    }
}
