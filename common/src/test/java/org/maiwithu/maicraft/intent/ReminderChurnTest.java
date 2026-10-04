// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.maiwithu.maicraft.client.runtime.DrowningReminder;
import org.maiwithu.maicraft.client.runtime.GearDurabilityReminder;

/** 回放角色贴水面上下浮动，确认提醒抖动不会挤掉同一 attention 流中真正等待回答的任务事件。 */
public final class ReminderChurnTest {
    public static void main(String[] args) throws Exception {
        waterlineKeepsDecisionHistory();
        temporaryToolChangesStayQuiet();
        changingMessagesDoNotHideDamage();
        System.out.println("ReminderChurnTest: passed");
    }

    private static void waterlineKeepsDecisionHistory() throws Exception {
        var feed = new AttentionFeed();
        var task = UUID.randomUUID();
        feed.publish("decision", task, "等待模型选择下一步", new JsonObject());
        var signals = new AtomicInteger();
        var clock = new AtomicLong();
        try (var subscription = feed.subscribe(ignored -> signals.incrementAndGet())) {
            var board = new ReminderBoard(event -> feed.publish("agent.reminder", null,
                    event.get("message").getAsString(), event), clock::get);
            var drowning = new DrowningReminder(board);
            for (int tick = 0; tick < 400; tick++) {
                clock.set(tick * 50_000_000L);
                drowning.observe(new DrowningReminder.Observation(tick, tick % 2 == 0, 100, 300));
                board.flush();
                check(board.snapshot().size() == (tick % 2 == 0 ? 1 : 0), "当前快照必须立即反映头部是否还在水下");
            }
            check(signals.get() == 1, "贴水面反复进出不应反复唤醒，实际提醒事件数=" + signals.get());
            var page = feed.read(0, 256, null, task);
            check(!page.get("history_lost").getAsBoolean()
                    && page.getAsJsonArray("events").get(0).getAsJsonObject().get("type").getAsString().equals("decision"),
                    "提醒不能冲掉任务决策历史");
        }
    }

    private static void temporaryToolChangesStayQuiet() {
        var events = new ArrayList<JsonObject>();
        var clock = new AtomicLong();
        var board = new ReminderBoard(events::add, clock::get);
        var gear = new GearDurabilityReminder(board);
        // 镐快坏时临时切成方块或食物，再切回原镐；快照反映当前主手，通知仍视为同一项准备问题。
        for (int tick = 0; tick <= 1000; tick += 20) {
            clock.set(tick * 50_000_000L);
            boolean holdingPickaxe = tick % 40 == 0;
            gear.observe(new GearDurabilityReminder.Observation(tick, holdingPickaxe
                    ? List.of(new GearDurabilityReminder.SlotFact("mainhand", "minecraft:wooden_pickaxe", 4, 59)) : List.of()));
            board.flush();
            check(board.snapshot().size() == (holdingPickaxe ? 1 : 0), "切主手后快照及时改变，不能为了防刷屏保留旧事实");
        }
        check(events.size() == 1, "频繁切回快坏的工具不能每次都被当作第一次危险");
    }

    private static void changingMessagesDoNotHideDamage() throws Exception {
        var feed = new AttentionFeed();
        var clock = new AtomicLong();
        var task = UUID.randomUUID();
        feed.publish("decision", task, "仍需回答的决定", new JsonObject());
        var board = new ReminderBoard(event -> feed.publish("agent.reminder", null,
                event.get("message").getAsString(), event), clock::get);
        // 八条建议各自变文案，且世界刻反复校正；同一状态最后的完整事实仍必须在工具快照中。
        for (int tick = 0; tick < 400; tick++) {
            clock.set(tick * 50_000_000L);
            for (int id = 0; id < 8; id++) {
                var facts = new JsonObject(); facts.addProperty("observation", tick);
                board.update("risk_" + id, "状态 " + tick % 2, facts, new JsonArray(), tick % 2 == 0 ? 10_000 : 1);
            }
            board.flush();
        }
        check(board.snapshot().size() == 8 && board.snapshot().asList().stream()
                .allMatch(value -> value.getAsJsonObject().getAsJsonObject("evidence").get("observation").getAsInt() == 399),
                "八条提醒分别保留最新完整事实");
        feed.publish("agent.damaged", null, "真实受伤必须继续唤醒", new JsonObject());
        var page = feed.read(0, 256, null, task);
        check(!page.get("history_lost").getAsBoolean() && page.getAsJsonArray("events").size() == 10,
                "仅首次八条建议入流，决策和真实受伤都完整保留");
        check(page.getAsJsonArray("events").get(9).getAsJsonObject().get("type").getAsString().equals("agent.damaged"),
                "提醒冷却不影响真实受伤事件");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
