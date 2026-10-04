// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.ArrayList;
import java.util.List;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 用真实装备包驱动耐久打空观察；只认本玩家的同步，基准外首包不补发旧变化。 */
public final class EquipmentReceiptsTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        reportsBrokenWithConsumption();
        ignoresOtherEntities();
        swapResetsAccumulation();
        bodyChangeRebases();
        System.out.println("EquipmentReceiptsTest: equipment sync observation and durability-break receipts passed");
    }

    private static void reportsBrokenWithConsumption() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var events = new ArrayList<String>();
            try (var ignored = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                // 首包只建立基准；同物品耐久下降只累计，不打扰。
                equip(world, EquipmentSlot.MAINHAND, pickaxe(0));
                check(events.stream().noneMatch(EquipmentReceiptsTest::mentionsBroken), "首包装备必须只建立基准");
                equip(world, EquipmentSlot.MAINHAND, pickaxe(100));
                check(events.stream().noneMatch(EquipmentReceiptsTest::mentionsBroken), "耐久下降不打扰，只累计");
                // 服务端打空后以空栈同步该槽位：这就是物品消灭的显式证据。
                equip(world, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                var broken = events.stream().filter(EquipmentReceiptsTest::mentionsBroken).toList();
                check(broken.size() == 1, "打空必须恰好发一次 item_broken");
                String event = broken.getFirst();
                check(event.contains("\"item_id\":\"minecraft:stone_pickaxe\""), "事件必须点名打空的物品");
                check(event.contains("\"equipment_slot\":\"mainhand\""), "事件必须写明槽位");
                check(event.contains("\"durability_last_seen\":31"), "事件必须携带最后一次同步到的剩余耐久");
                check(event.contains("\"damage_observed_total\":100"), "事件必须累计观察到的耐久消耗");
                check(event.contains("agent.item_broken"), "事件类型固定为 agent.item_broken");
            }
        }
    }

    private static void ignoresOtherEntities() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var events = new ArrayList<String>();
            try (var ignored = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                equip(world, EquipmentSlot.MAINHAND, pickaxe(0));
                int strangerId = world.player.getId() + 1;
                EquipmentReceipts.equipped(world.player, packet(strangerId, EquipmentSlot.MAINHAND, pickaxe(0)));
                EquipmentReceipts.equipped(world.player, packet(strangerId, EquipmentSlot.MAINHAND, pickaxe(60)));
                EquipmentReceipts.equipped(world.player, packet(strangerId, EquipmentSlot.MAINHAND, ItemStack.EMPTY));
                check(events.isEmpty(), "其他实体的装备包不得发布事件，也不得建立本地基准");
                equip(world, EquipmentSlot.MAINHAND, pickaxe(100));
                equip(world, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                var broken = events.stream().filter(EquipmentReceiptsTest::mentionsBroken).toList();
                check(broken.size() == 1 && broken.getFirst().contains("\"damage_observed_total\":100"),
                        "陌生实体的耐久账不得混入本地消耗累计");
            }
        }
    }

    private static void swapResetsAccumulation() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var events = new ArrayList<String>();
            try (var ignored = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                equip(world, EquipmentSlot.OFFHAND, pickaxe(0));
                equip(world, EquipmentSlot.OFFHAND, pickaxe(80));
                equip(world, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                equip(world, EquipmentSlot.OFFHAND, pickaxe(0));
                equip(world, EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                var broken = events.stream().filter(EquipmentReceiptsTest::mentionsBroken).toList();
                check(broken.size() == 1 && broken.getFirst().contains("\"damage_observed_total\":0"),
                        "手动换装后消耗累计必须清零，不得把旧物品的耐久账算到新物品头上");
                check(broken.getFirst().contains("\"equipment_slot\":\"offhand\""), "非主手槽位同样报告");
            }
        }
    }

    private static void bodyChangeRebases() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            equip(world, EquipmentSlot.MAINHAND, pickaxe(0));
            equip(world, EquipmentSlot.MAINHAND, pickaxe(90));
        }
        try (var nextBody = new InteractionWorldTestHarness()) {
            var events = new ArrayList<String>();
            try (var ignored = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                equip(nextBody, EquipmentSlot.MAINHAND, pickaxe(90));
                check(events.stream().noneMatch(EquipmentReceiptsTest::mentionsBroken),
                        "换身体后的首包只建立基准，不得把上一具身体的耐久账补发出来");
            }
        }
    }

    private static ItemStack pickaxe(int damage) {
        ItemStack stack = new ItemStack(Items.STONE_PICKAXE);
        stack.setDamageValue(damage);
        return stack;
    }

    private static void equip(InteractionWorldTestHarness world, EquipmentSlot slot, ItemStack stack) {
        EquipmentReceipts.equipped(world.player, packet(world.player.getId(), slot, stack));
    }

    private static ClientboundSetEquipmentPacket packet(int entityId, EquipmentSlot slot, ItemStack stack) {
        return new ClientboundSetEquipmentPacket(entityId, List.of(Pair.of(slot, stack)));
    }

    private static boolean mentionsBroken(String event) {
        return event.contains("agent.item_broken");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
