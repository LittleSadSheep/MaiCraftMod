// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.CombatEquipmentReminder;
import org.maiwithu.maicraft.client.runtime.FoodSupplyReminder;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.client.runtime.GameplayReminders;
import org.maiwithu.maicraft.client.runtime.LowLightCombatReminder;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 真实物品组件、原生攻击包和角色红心共同驱动提醒，验证多个生活建议能共存且不执行任何动作。 */
public final class SurvivalRemindersTest {
    public static void main(String[] args) throws Exception {
        try {
            coexistAndRecover();
            delayedHealthAndUnrelatedDamage();
            clearsSurvivalHistory();
        } finally { GameplayReminders.reset(); }
        System.out.println("SurvivalRemindersTest: passed");
    }

    private static void coexistAndRecover() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset(); f.h.level.localLight = 0;
            hungryWithFlesh(f);
            observe(f);
            var zombie = f.mob(11, 2);
            for (int i = 1; i <= 3; i++) {
                f.h.level.time++; f.h.player.setHealth(20 - i * 3); f.hit(zombie, zombie); observe(f);
            }
            check(GameplayReminders.snapshot().size() == 3 && reminder(LowLightCombatReminder.ID) != null,
                    "缺粮、低光和战斗准备三条提醒同时存在，不会相互覆盖");
            check(reminder(FoodSupplyReminder.ID).getAsJsonObject("evidence").get("rotten_flesh_carried").getAsInt() == 16,
                    "副手腐肉属于实际随身库存");
            JsonObject combat = reminder(CombatEquipmentReminder.ID).getAsJsonObject("evidence");
            check(combat.get("observed_health_loss_near_attacks").getAsFloat() == 9
                    && !combat.getAsJsonObject("equipment").get("ranged_weapon_carried").getAsBoolean(),
                    "战斗提醒携带累计红心变化与缺少远程武器的现场事实");
            // 正常食物、弓和箭分别改变对应准备条件；装备在背包里不会被提醒系统自动穿戴或使用。
            f.h.inventory.offhand.set(0, new ItemStack(Items.BREAD, 4));
            f.h.level.time += 20; GameplayReminders.tick(f.h.player);
            check(reminder(FoodSupplyReminder.ID) == null, "副手普通口粮恢复后撤下种地建议");
            f.h.inventory.setItem(0, new ItemStack(Items.BOW)); f.h.inventory.setItem(1, new ItemStack(Items.ARROW, 8));
            f.h.level.time++; observe(f);
            check(reminder(CombatEquipmentReminder.ID) == null, "新出现弓与箭后等待新的战斗证据");
            f.h.level.localLight = 15; GameplayReminders.tick(f.h.player);
            check(GameplayReminders.snapshot().isEmpty(), "三条提醒分别按实际观察条件撤下");
            check(f.h.mode.items == 0 && f.h.mode.blocks == 0 && f.h.mode.attacks == 0,
                    "提醒不会吃腐肉、种地、穿甲、开弓或抢占当前动作");
        }
    }

    private static void delayedHealthAndUnrelatedDamage() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset(); observe(f);
            var zombie = f.mob(11, 2);
            for (int i = 0; i < 3; i++) f.hit(zombie, zombie);
            observe(f);
            check(reminder(CombatEquipmentReminder.ID) == null, "三包先到但红心没变，不编造重伤");
            f.h.level.time++; f.h.player.setHealth(13); observe(f);
            check(reminder(CombatEquipmentReminder.ID).getAsJsonObject("evidence")
                    .get("observed_health_loss_near_attacks").getAsFloat() == 7, "无新伤害包的一刻接住迟到血量，且只算一次");
            GameplayReminders.reset(); f.h.player.setHealth(20); observe(f);
            for (int i = 0; i < 3; i++) f.hit(zombie, zombie);
            observe(f);
            f.h.level.time++; f.h.player.setHealth(5); f.hit(null, null); observe(f);
            check(reminder(CombatEquipmentReminder.ID) == null, "明确的环境伤害中断先前攻击的血量归因");
        }
    }

    private static void clearsSurvivalHistory() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset(); hungryWithFlesh(f); observe(f);
            var zombie = f.mob(11, 2);
            f.h.level.time++; f.h.player.setHealth(8); f.hit(zombie, zombie); observe(f);
            check(reminder(FoodSupplyReminder.ID) != null && reminder(CombatEquipmentReminder.ID) != null, "先积累两类生存压力");
            f.h.player.getAbilities().instabuild = true; GameplayReminders.tick(f.h.player);
            check(GameplayReminders.snapshot().isEmpty(), "创造模式不携带生存补给提醒");
            f.h.player.getAbilities().instabuild = false; GameplayReminders.tick(f.h.player); observe(f);
            check(GameplayReminders.snapshot().isEmpty(), "回到生存重新计时，不复活之前的饥饿或战斗记录");
        }
    }

    private static void hungryWithFlesh(CombatThreatsTest.Fixture f) {
        f.h.player.getFoodData().setFoodLevel(12); f.h.inventory.offhand.set(0, new ItemStack(Items.ROTTEN_FLESH, 16));
        for (int tick = 0; tick <= 1200; tick += 20) { f.h.level.time = tick; GameplayReminders.tick(f.h.player); }
    }
    private static void observe(CombatThreatsTest.Fixture f) {
        GameplayAttentionMonitor.observeDamagePackets(f.h.player, f.h.player.getHealth(), f.h.player.getHealth());
    }
    private static JsonObject reminder(String id) {
        return GameplayReminders.snapshot().asList().stream().map(value -> value.getAsJsonObject())
                .filter(value -> value.get("id").getAsString().equals(id)).findFirst().orElse(null);
    }
}
