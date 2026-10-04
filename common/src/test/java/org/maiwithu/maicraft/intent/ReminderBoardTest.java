// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 回放真实快照与延后通知：状态反转只合并事件，不能延迟事实、丢掉解除后的冷却或重放过时状态。 */
public final class ReminderBoardTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var clock = new AtomicLong();
        var board = new ReminderBoard(events::add, clock::get);
        JsonObject evidence = new JsonObject(); evidence.addProperty("hits", 3);
        JsonArray suggestions = new JsonArray(); suggestions.add("检查补光");
        update(board, clock, 100, "频繁遇袭且昏暗", evidence, suggestions);
        check(events.size() == 1 && board.snapshot().size() == 1, "首次危险同时进入事件和常驻快照");
        // 事件订阅者、规则输入和工具读者都可能修改自己的 JSON，不能改写下一次返回的现场证据。
        events.getFirst().addProperty("id", "changed");
        evidence.addProperty("hits", 4); suggestions.add("准备材料");
        board.snapshot().get(0).getAsJsonObject().addProperty("id", "changed");
        JsonObject first = board.snapshot().get(0).getAsJsonObject();
        check(first.get("id").getAsString().equals("dark_combat")
                && first.getAsJsonObject("evidence").get("hits").getAsInt() == 3
                && first.getAsJsonArray("suggested_actions").size() == 1, "快照完整且与读写者隔离");
        update(board, clock, 699, "频繁遇袭且昏暗", evidence, suggestions);
        check(events.size() == 1 && board.snapshot().get(0).getAsJsonObject()
                .getAsJsonObject("evidence").get("hits").getAsInt() == 4, "节流只影响事件，不遮住最新证据");
        update(board, clock, 700, "伤势加重", evidence, suggestions);
        check(events.size() == 1 && board.snapshot().toString().contains("伤势加重"), "文案立即进入快照，事件等待稳定");
        update(board, clock, 739, "伤势加重", evidence, suggestions);
        check(events.size() == 1, "稳定不足两秒不发变更通知");
        evidence.addProperty("hits", 7);
        update(board, clock, 740, "伤势加重", evidence, suggestions);
        check(events.size() == 2 && events.getLast().getAsJsonObject("evidence").get("hits").getAsInt() == 7,
                "稳定两秒且距上次通知已满三十秒，交付最新证据而不是首次变化的旧快照");
        update(board, clock, 12_739, "伤势加重", evidence, suggestions);
        check(events.size() == 2, "持续状态在定期窗口内保持安静");
        update(board, clock, 12_740, "伤势加重", evidence, suggestions);
        check(events.size() == 3, "持续状态按单调时钟每十分钟定期唤醒一次");
        // 多条规则按自己的身份维护，撤下一条不能让仍有依据的另一条消失。
        board.update("another_rule", "另一处风险", evidence, suggestions, 12_740);
        at(clock, 12_741); board.remove("dark_combat", "现场已明亮", 12_741); board.flush();
        check(events.size() == 4 && board.snapshot().size() == 1, "风险解除立即撤下快照，解除事件仍受冷却约束");
        at(clock, 12_742); board.remove("dark_combat", "最新解除依据", 12_742);
        at(clock, 13_339); board.flush();
        check(events.size() == 4, "持续观察解除不会重置稳定时间，也不能提前绕过通知冷却");
        at(clock, 13_340); board.flush();
        check(events.size() == 5 && board.snapshot().size() == 1
                && events.getLast().get("status").getAsString().equals("cleared")
                && events.getLast().get("reason").getAsString().equals("最新解除依据"), "稳定解除只发一次并保留最后依据");
        update(board, clock, 13_341, "伤势加重", evidence, suggestions);
        check(events.size() == 5 && board.snapshot().size() == 2, "重现立即可读，但沿用解除前后的同一 ID 冷却");
        update(board, clock, 13_939, "伤势加重", evidence, suggestions);
        check(events.size() == 5, "重现不能作为首次危险重复发送");
        update(board, clock, 13_940, "伤势加重", evidence, suggestions);
        check(events.size() == 6 && events.getLast().get("status").getAsString().equals("active"), "持续重现到期后仍会通知，不会永久静音");
        // 世界校时与通知时钟分离；回拨游戏刻只更新事实，不能重置冷却或强迫模型再次醒来。
        at(clock, 13_941); board.update("dark_combat", "待合并的新文案", evidence, suggestions, 1); board.flush();
        check(events.size() == 6 && board.snapshot().toString().contains("待合并的新文案"), "世界时钟回退不能绕开冷却");
        board.clear();
        at(clock, 30_000); board.flush();
        check(board.snapshot().isEmpty() && events.size() == 6, "世界切换静默丢弃旧提醒与尚未发出的通知");
        update(board, clock, 30_000, "新身体的首次危险", evidence, suggestions);
        check(events.size() == 7, "旧世界冷却不能遮住新身体的首次危险");
        System.out.println("ReminderBoardTest: passed");
    }

    private static void update(ReminderBoard board, AtomicLong clock, long tick, String message,
                               JsonObject evidence, JsonArray suggestions) {
        at(clock, tick); board.update("dark_combat", message, evidence, suggestions, tick); board.flush();
    }
    private static void at(AtomicLong clock, long tick) { clock.set(tick * 50_000_000L); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
