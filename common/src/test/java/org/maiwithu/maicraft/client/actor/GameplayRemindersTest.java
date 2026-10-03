// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.client.runtime.GameplayReminders;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.intent.IntentRuntime;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 沿真实伤害包→观察器→提醒→注意流回放，并核对提醒没有替角色执行动作。 */
public final class GameplayRemindersTest {
    public static void main(String[] args) throws Exception {
        try {
            filtersUnrelatedDamage();
            keepsEvidenceAndClearsLifecycle();
        } finally { GameplayReminders.reset(); }
        System.out.println("GameplayRemindersTest: passed");
    }

    private static void filtersUnrelatedDamage() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset(); f.h.level.localLight = 0;
            var wolf = f.mob(DamageAttentionTest.TestWolf.class, EntityType.WOLF, 21, 2);
            var visitor = PvpTestPlayers.create(f, 22, 2);
            for (int i = 0; i < 4; i++) {
                hit(f, null); hit(f, wolf);
                // 玩家攻击仍由原有玩家注意逻辑处理，此处只核对补光规则不会把玩家当作刷出的怪物。
                GameplayReminders.damaged(f.h.player, visitor);
                GameplayAttentionMonitor.observeHealthDrop(f.h.player, 20, 19);
            }
            check(GameplayReminders.snapshot().isEmpty(), "环境包、中立生物、玩家及未确认掉血都不累计敌怪补光依据");
            var zombie = f.mob(11, 2);
            f.h.level.localLight = 15;
            for (int i = 0; i < 4; i++) hit(f, zombie);
            check(GameplayReminders.snapshot().isEmpty(), "太阳明亮的脚下不能只因方块光为零提醒黑暗");
        }
    }

    private static void keepsEvidenceAndClearsLifecycle() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset(); f.h.level.localLight = 0;
            var events = new ArrayList<JsonObject>();
            long cursor = IntentRuntime.get().attentionCheckpoint().get("cursor").getAsLong();
            try (var subscription = IntentRuntime.get().subscribeAttention(page -> page.getAsJsonObject()
                    .getAsJsonArray("events").forEach(event -> {
                        if (event.getAsJsonObject().get("type").getAsString().equals("agent.reminder"))
                            events.add(event.getAsJsonObject());
                    }))) {
                var zombie = f.mob(11, 2);
                for (int i = 0; i < 3; i++) hit(f, zombie);
                check(GameplayReminders.snapshot().size() == 1 && events.size() == 1
                        && events.getFirst().get("priority").getAsString().equals("important"), "连续敌怪包发布重要提醒");
                check(IntentRuntime.get().attention(cursor, 50, null, UUID.randomUUID()).getAsJsonArray("events")
                        .asList().stream().anyMatch(e -> e.getAsJsonObject().get("type").getAsString().equals("agent.reminder")),
                        "只等待某个任务时仍能收到全局风险提醒");
                GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 20);
                check(GameplayReminders.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence")
                        .get("hit_count").getAsInt() == 3 && CombatThreats.attackers(f.h.player).contains(zombie),
                        "重复读取不重复计数，也不消费自卫的攻击者记忆");
                check(f.h.mode.blocks == 0 && f.h.mode.items == 0 && f.h.mode.attacks == 0,
                        "提醒不能自行插火把、使用物品或反击");
                f.h.level.lightAvailable = false; GameplayReminders.tick(f.h.player);
                check(GameplayReminders.snapshot().isEmpty(), "照度不可用时不保留未经复核的低光建议");
                f.h.level.lightAvailable = true; GameplayReminders.tick(f.h.player);
                check(GameplayReminders.snapshot().size() == 1, "现场再次可测时可恢复仍在窗口内的真实攻击依据");
                f.h.level.time += 1200; GameplayReminders.tick(f.h.player);
                check(GameplayReminders.snapshot().isEmpty(), "没有新伤害也会按游戏时间撤下过期提醒");
                for (int i = 0; i < 3; i++) hit(f, zombie);
                GameplayAttentionMonitor.reset();
                check(GameplayReminders.snapshot().isEmpty(), "断线观察重置清空旧提醒");
                for (int i = 0; i < 3; i++) hit(f, zombie);
            }
        }
        // 同维度换了玩家实例也必须清空，不能等下一次挨打才发现旧提醒属于上一条命。
        try (var next = new CombatThreatsTest.Fixture()) {
            next.h.level.localLight = 0; GameplayReminders.tick(next.h.player);
            check(GameplayReminders.snapshot().isEmpty(), "新身体不继承旧提醒");
            var zombie = next.mob(11, 2);
            for (int i = 0; i < 3; i++) hit(next, zombie);
            next.h.player.setHealth(0); GameplayReminders.tick(next.h.player);
            check(GameplayReminders.snapshot().isEmpty(), "死亡当刻撤下旧身体的工作建议");
        }
    }

    private static void hit(CombatThreatsTest.Fixture f, Entity attacker) {
        f.h.level.time += 20;
        f.hit(attacker, attacker);
        GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 20);
    }
}
