// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;

/** 回放持续危险的多读者投递、事件节流及现场变化，避免一次读取消费掉其他模型的提醒。 */
public final class ReminderBoardTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        JsonObject evidence = new JsonObject(); evidence.addProperty("hits", 3);
        JsonArray suggestions = new JsonArray(); suggestions.add("检查补光");
        board.update("dark_combat", "频繁遇袭且昏暗", evidence, suggestions, 100);
        check(events.size() == 1 && board.snapshot().size() == 1, "首次危险同时进入事件和常驻快照");
        // 事件订阅者、规则输入和工具读者都可能修改自己的 JSON，不能改写下一次返回的现场证据。
        events.getFirst().addProperty("id", "changed");
        evidence.addProperty("hits", 4); suggestions.add("准备材料");
        board.snapshot().get(0).getAsJsonObject().addProperty("id", "changed");
        JsonObject first = board.snapshot().get(0).getAsJsonObject();
        check(first.get("id").getAsString().equals("dark_combat")
                && first.getAsJsonObject("evidence").get("hits").getAsInt() == 3
                && first.getAsJsonArray("suggested_actions").size() == 1, "快照完整且与读写者隔离");
        board.update("dark_combat", "仍然遇袭", evidence, suggestions, 699);
        check(events.size() == 1 && board.snapshot().get(0).getAsJsonObject()
                .getAsJsonObject("evidence").get("hits").getAsInt() == 4, "节流只影响事件，不遮住最新证据");
        board.update("dark_combat", "仍然遇袭", evidence, suggestions, 700);
        check(events.size() == 2, "持续危险在三十秒游戏时间后允许再次唤醒");
        // 多条规则按自己的身份维护，撤下一条不能让仍有依据的另一条消失。
        board.update("another_rule", "另一处风险", evidence, suggestions, 700);
        board.remove("dark_combat", "现场已明亮", 701);
        board.remove("dark_combat", "现场已明亮", 702);
        check(events.size() == 4 && board.snapshot().size() == 1
                && events.getLast().get("status").getAsString().equals("cleared"), "撤下只发一次且不冒称问题已解决");
        board.clear();
        check(board.snapshot().isEmpty() && events.size() == 4, "世界切换静默丢弃旧提醒");
        System.out.println("ReminderBoardTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
