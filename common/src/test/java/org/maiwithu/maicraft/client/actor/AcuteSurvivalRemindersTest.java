// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import org.maiwithu.maicraft.client.runtime.BurningExposureReminder;
import org.maiwithu.maicraft.client.runtime.DrowningReminder;
import org.maiwithu.maicraft.client.runtime.GameplayReminders;
import org.maiwithu.maicraft.client.runtime.GearDurabilityReminder;
import org.maiwithu.maicraft.client.runtime.InventorySpaceReminder;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 溺水、着火、耐久与背包提醒都来自真实身体同步状态和随身物品，验证多条共存、按观察撤下且不执行任何动作。 */
public final class AcuteSurvivalRemindersTest {
    public static void main(String[] args) throws Exception {
        try {
            drowningAndBurning();
            durabilityAndInventory();
            resetsWithBody();
        } finally { GameplayReminders.reset(); }
        System.out.println("AcuteSurvivalRemindersTest: passed");
    }

    private static void drowningAndBurning() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset();
            submerge(f);
            run(f, 1);
            check(reminder(DrowningReminder.ID) != null, "水下氧气过半逐刻观察立即提醒上浮");
            check(reminder(DrowningReminder.ID).getAsJsonObject("evidence").get("air_supply").getAsInt() == 100,
                    "提醒携带真实氧气余量");
            surface(f);
            run(f, 1);
            check(reminder(DrowningReminder.ID) == null, "出水且氧气恢复后撤下");
            f.h.player.setRemainingFireTicks(40);
            run(f, 5);
            check(reminder(BurningExposureReminder.ID) == null, "火焰掠过不满一秒不催促");
            run(f, 20);
            var burning = reminder(BurningExposureReminder.ID);
            check(burning != null && burning.getAsJsonObject("evidence").get("on_fire").getAsBoolean()
                    && !burning.getAsJsonObject("evidence").get("in_lava").getAsBoolean(),
                    "持续着火一秒后提醒并区分着火与岩浆");
            f.h.player.setRemainingFireTicks(-1);
            run(f, 1);
            check(reminder(BurningExposureReminder.ID) == null, "火灭后撤下");
            fluidHeight(f).put(FluidTags.LAVA, 1.0);
            run(f, 25);
            burning = reminder(BurningExposureReminder.ID);
            check(burning != null && burning.getAsJsonObject("evidence").get("in_lava").getAsBoolean()
                    && !burning.getAsJsonObject("evidence").get("on_fire").getAsBoolean(),
                    "岩浆暴露同样触发燃烧提醒且此刻并未着火");
            fluidHeight(f).removeDouble(FluidTags.LAVA);
            run(f, 1);
            check(GameplayReminders.snapshot().isEmpty(), "脱离岩浆后全部急性提醒撤下");
            check(f.h.mode.items == 0 && f.h.mode.blocks == 0 && f.h.mode.attacks == 0,
                    "提醒不会自动上浮、灭火、换装或抢占当前动作");
        }
    }

    private static void durabilityAndInventory() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset();
            var pick = new ItemStack(Items.WOODEN_PICKAXE);
            pick.setDamageValue(55);
            f.h.inventory.setItem(0, pick);
            run(f, 21);
            var durability = reminder(GearDurabilityReminder.ID);
            check(durability != null && durability.getAsJsonObject("evidence").getAsJsonArray("observed_slots")
                    .get(0).getAsJsonObject().get("remaining_durability").getAsInt() == 4,
                    "主手工具耐久将尽按秒盘点提醒");
            f.h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            run(f, 21);
            check(reminder(GearDurabilityReminder.ID) == null, "换上满耐久工具后撤下");
            for (int slot = 0; slot < 34; slot++) f.h.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE));
            run(f, 81);
            var space = reminder(InventorySpaceReminder.ID);
            check(space != null && space.getAsJsonObject("evidence").get("free_slots").getAsInt() == 2,
                    "背包持续装满三秒后提醒");
            f.h.inventory.clearContent();
            run(f, 21);
            check(reminder(InventorySpaceReminder.ID) == null && reminder(GearDurabilityReminder.ID) == null,
                    "清空背包后空间与耐久提醒一并按现状撤下");
            check(f.h.mode.items == 0 && f.h.mode.blocks == 0 && f.h.mode.attacks == 0,
                    "提醒不会自动丢弃物品、清包或更换工具");
        }
    }

    private static void resetsWithBody() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            GameplayReminders.reset();
            submerge(f);
            f.h.player.setRemainingFireTicks(40);
            var pick = new ItemStack(Items.WOODEN_PICKAXE);
            pick.setDamageValue(55);
            f.h.inventory.setItem(0, pick);
            run(f, 25);
            check(GameplayReminders.snapshot().size() == 3, "溺水、着火与耐久三条提醒按 ID 共存");
            f.h.player.getAbilities().instabuild = true;
            run(f, 1);
            check(GameplayReminders.snapshot().isEmpty(), "创造模式不携带急性状态与随身资源提醒");
            f.h.player.getAbilities().instabuild = false;
            // 着火是当下真实状态，切回生存后如实重报；只有状态恢复安全才应保持安静。
            surface(f);
            f.h.player.setRemainingFireTicks(-1);
            f.h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            run(f, 25);
            check(GameplayReminders.snapshot().isEmpty(), "回到生存且状态安全后保持安静，不复活之前的记录");
        }
    }

    /**
     * LocalPlayer.isUnderWater 读的是客户端 tick 维护的 wasUnderwater 缓存而不是底层流体字段，
     * 夹具直接提供缓存值；isInLava 排除首刻，绕过构造器创建的夹具角色要显式越过首刻。
     */
    private static void submerge(CombatThreatsTest.Fixture f) throws Exception {
        ActorControlTestHarness.field(LocalPlayer.class, "wasUnderwater").setBoolean(f.h.player, true);
        ActorControlTestHarness.field(Entity.class, "firstTick").setBoolean(f.h.player, false);
        f.h.player.setAirSupply(100);
    }

    private static void surface(CombatThreatsTest.Fixture f) throws Exception {
        ActorControlTestHarness.field(LocalPlayer.class, "wasUnderwater").setBoolean(f.h.player, false);
        f.h.player.setAirSupply(f.h.player.getMaxAirSupply());
    }

    @SuppressWarnings("unchecked")
    private static Object2DoubleMap<TagKey<Fluid>> fluidHeight(CombatThreatsTest.Fixture f) throws Exception {
        return (Object2DoubleMap<TagKey<Fluid>>) ActorControlTestHarness.field(Entity.class, "fluidHeight")
                .get(f.h.player);
    }

    private static void run(CombatThreatsTest.Fixture f, int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            f.h.level.time++;
            GameplayReminders.tick(f.h.player);
        }
    }

    private static JsonObject reminder(String id) {
        return GameplayReminders.snapshot().asList().stream().map(value -> value.getAsJsonObject())
                .filter(value -> value.get("id").getAsString().equals(id)).findFirst().orElse(null);
    }
}
