// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.runtime.NativeRestStatistics;
import org.maiwithu.maicraft.client.runtime.GameplayReminders;
import org.maiwithu.maicraft.client.runtime.SleepReminder;
import org.maiwithu.maicraft.intent.ReminderBoard;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 真实统计包提供休息计数，夹具只控制服务器时间与入睡状态；整个测试不点击床或打开统计界面。 */
public final class NativeRestStatisticsTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var time = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(h.level, time);
            SleepSafetyTest.bedWorks(h, true); time.setDayTime(100 * 24_000L + 6000);
            var board = new ReminderBoard(event -> {});
            var source = new NativeRestStatistics(new SleepReminder(board));
            source.tick(h.player);
            check(requests(h) == 1 && board.snapshot().isEmpty(), "刚连接只查询个人统计，不从世界日期猜测失眠");
            source.received(h.player, packet(null)); source.tick(h.player);
            check(board.snapshot().isEmpty(), "缺少休息字段的包既不是零，也不是未睡多日的证明");
            source.received(h.player, packet(80_000)); source.tick(h.player);
            check(value(board).get("message").getAsString().startsWith("建议提前备床"), "服务端计数达到三天后白天提前准备床");
            time.setDayTime(18_000); h.level.time++; source.tick(h.player);
            check(value(board).get("message").getAsString().startsWith("你已经长时间没有睡觉"), "夜晚文案随当前世界时间改变");
            h.level.time = 600; source.tick(h.player);
            check(requests(h) == 1, "三十秒窗口内不会不断请求统计");
            h.level.time = 601; source.tick(h.player);
            check(requests(h) == 2, "到期后才续读一次原生统计");
            h.level.time = 1202; source.received(h.player, packet(null)); source.tick(h.player);
            check(board.snapshot().isEmpty(), "无关统计包不能把一分钟前的休息计数续成当前事实");
            source.received(h.player, packet(90_000)); source.tick(h.player);
            check(board.snapshot().size() == 1, "重新收到个人统计后恢复有依据的提醒");
            ActorControlTestHarness.field(h.player.getClass(), "sleeping").setBoolean(h.player, true);
            h.level.time++; source.tick(h.player);
            check(board.snapshot().isEmpty(), "看到原生入睡立即撤下，不必等睡满整夜");
            source.received(h.player, packet(90_000));
            ActorControlTestHarness.field(h.player.getClass(), "sleeping").setBoolean(h.player, false);
            h.level.time++; source.received(h.player, packet(90_000)); source.tick(h.player);
            check(board.snapshot().isEmpty(), "入睡前旧请求的迟到高计数不能在醒来后复活提醒");
            source.received(h.player, packet(0)); source.tick(h.player);
            check(board.snapshot().isEmpty(), "新收到的原生零值才作为计数已重置的事实");
            // 真正再次经历三天后允许新一轮提醒，不能把本次入睡永远当成免提醒标记。
            h.level.time += 72_100; source.received(h.player, packet(72_000)); source.tick(h.player);
            check(board.snapshot().size() == 1, "新一轮长期未睡可再次提醒");
            SleepSafetyTest.bedWorks(h, false); source.tick(h.player);
            check(value(board).get("message").getAsString().contains("当前维度不支持正常用床"), "按原生维度事实给出休息建议");
            h.level.time = 0; source.tick(h.player);
            check(board.snapshot().isEmpty(), "世界时钟回退使旧统计失效");
            h.h.connection.failSend = true; source.clear(); source.tick(h.player);
            check(board.snapshot().isEmpty(), "统计请求失败不抛出身体任务失败，也不冒称已休息");
            // 生产入口与纯采样器使用同一份原生统计；入睡后经角色观察自动撤下。
            h.h.connection.failSend = false; SleepSafetyTest.bedWorks(h, true); time.setDayTime(6000);
            GameplayReminders.reset(); GameplayReminders.receiveRestStatistics(h.player, packet(80_000));
            GameplayReminders.tick(h.player);
            check(GameplayReminders.snapshot().toString().contains(SleepReminder.ID), "统计回调贯通常驻提醒入口");
            ActorControlTestHarness.field(h.player.getClass(), "sleeping").setBoolean(h.player, true);
            GameplayReminders.tick(h.player);
            check(!GameplayReminders.snapshot().toString().contains(SleepReminder.ID), "真实入睡状态解除生产入口的提醒");
            GameplayReminders.reset();
            check(h.mode.blocks == 0 && h.mode.items == 0 && h.mode.menuClicks == 0, "观察提醒没有上床、开菜单或改变物品");
        }
        System.out.println("NativeRestStatisticsTest: passed");
    }

    static ClientboundAwardStatsPacket packet(Integer rest) {
        var stats = new Object2IntOpenHashMap<Stat<?>>();
        if (rest != null) stats.put(Stats.CUSTOM.get(Stats.TIME_SINCE_REST), rest.intValue());
        return new ClientboundAwardStatsPacket(stats);
    }
    private static long requests(InteractionWorldTestHarness h) {
        return h.h.connection.packets.stream().filter(packet -> packet instanceof ServerboundClientCommandPacket command
                && command.getAction() == ServerboundClientCommandPacket.Action.REQUEST_STATS).count();
    }
    private static JsonObject value(ReminderBoard board) {
        check(board.snapshot().size() == 1, "应有一条当前睡眠提醒");
        return board.snapshot().get(0).getAsJsonObject();
    }
}
