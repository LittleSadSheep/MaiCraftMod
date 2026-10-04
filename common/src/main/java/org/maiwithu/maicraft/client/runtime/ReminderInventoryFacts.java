// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.maiwithu.maicraft.core.inventory.OrdinaryFood;

/** 提醒只读取当前随身物品与实穿护甲，不打开容器、不搬运物品，也不替模型给装备排强弱等级。 */
final class ReminderInventoryFacts {
    private ReminderInventoryFacts() {}

    static FoodSupplyReminder.Observation food(LocalPlayer player) {
        long count = 0, nutrition = 0, flesh = 0;
        var inventory = player.getInventory();
        // 原生背包视图包含副手；腐肉单独作为应急库存事实，普通口粮沿用已有自动补食的组件定义。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.ROTTEN_FLESH)) flesh += stack.getCount();
            if (OrdinaryFood.ordinary(stack)) {
                count += stack.getCount();
                nutrition += (long) stack.getCount() * stack.get(DataComponents.FOOD).nutrition();
            }
        }
        return new FoodSupplyReminder.Observation(player.level().getGameTime(),
                player.getFoodData().getFoodLevel(), count, nutrition, flesh);
    }

    /** 耐久观察只看主手与实穿盔甲四个槽位；背包备件是否充足不在本采样范围内。 */
    static GearDurabilityReminder.Observation durability(LocalPlayer player) {
        List<GearDurabilityReminder.SlotFact> slots = new ArrayList<>();
        addWorn(slots, "mainhand", player.getMainHandItem());
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
            addWorn(slots, slot.name().toLowerCase(Locale.ROOT), player.getItemBySlot(slot));
        return new GearDurabilityReminder.Observation(player.level().getGameTime(), List.copyOf(slots));
    }

    private static void addWorn(List<GearDurabilityReminder.SlotFact> slots, String slot, ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return;
        slots.add(new GearDurabilityReminder.SlotFact(slot,
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
    }

    /** 空槽计数只算主背包三十六格；盔甲与副手槽位装不进普通战利品，混入会掩盖真实的装满程度。 */
    static InventorySpaceReminder.Observation space(LocalPlayer player) {
        var items = player.getInventory().items;
        int free = 0;
        for (ItemStack stack : items) if (stack.isEmpty()) free++;
        return new InventorySpaceReminder.Observation(player.level().getGameTime(), free, items.size());
    }

    static CombatEquipmentReminder.Observation equipment(LocalPlayer player) {
        var inventory = player.getInventory();
        boolean ranged = false, loaded = false; long arrows = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof ArrowItem) arrows += stack.getCount();
            if (stack.getItem() instanceof ProjectileWeaponItem) {
                ranged = true;
                var projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
                loaded |= projectiles != null && !projectiles.isEmpty();
            }
        }
        JsonArray armor = new JsonArray();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            var stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject(); item.addProperty("slot", slot.name().toLowerCase(Locale.ROOT));
            item.addProperty("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (stack.isDamageableItem()) item.addProperty("remaining_durability", stack.getMaxDamage() - stack.getDamageValue());
            armor.add(item);
        }
        JsonObject facts = new JsonObject(); facts.add("equipped_armor", armor);
        facts.addProperty("ranged_weapon_carried", ranged); facts.addProperty("arrows_carried", arrows);
        facts.addProperty("loaded_projectiles_carried", loaded);
        // 这只是可见的装备准备变化，不承诺弓已选中、弹药兼容或新装备足以打赢下一场战斗。
        return new CombatEquipmentReminder.Observation(player.level().getGameTime(), player.getHealth(),
                player.getMaxHealth(), player.getArmorValue(), ranged && (arrows > 0 || loaded), facts);
    }
}
