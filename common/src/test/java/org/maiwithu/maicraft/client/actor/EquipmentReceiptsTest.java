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

/** 用真实装备包与打空实体事件驱动耐久打空观察；只认本玩家的事实，基准外首包不补发旧变化。 */
public final class EquipmentReceiptsTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        reportsBrokenWithConsumption();
        ignoresOtherEntities();
        swapResetsAccumulation();
        bodyChangeRebases();
        entityEventPublishesWithScanBaseline();
        entityEventIgnoresStrangersAndNonBreakEvents();
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

    /**
     * 实机复测（057，2026-10-05）证得装备增量包从不发给本人，打空事实走 onEquippedItemBroken
     * 的 47 号实体事件；本场景按真实到达顺序驱动：逐刻扫描建账 → 打空 → 事件到达 → 发布。
     */
    private static void entityEventPublishesWithScanBaseline() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 打空事件按实体 ID 从世界里解析实体；夹具世界默认只有显式登记的实体。
            world.level.entities.put(world.player.getId(), world.player);
            var events = new ArrayList<String>();
            try (var ignored = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                world.player.setItemSlot(EquipmentSlot.MAINHAND, pickaxe(60));
                EquipmentReceipts.observe(world.player);
                world.player.setItemSlot(EquipmentSlot.MAINHAND, pickaxe(100));
                EquipmentReceipts.observe(world.player);
                check(events.isEmpty(), "逐刻扫描只建账与累计，不打扰");
                // 服务端打空后客户端背包同步为空栈：客户端侧的变化本身不是证据，不得发布。
                world.player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                EquipmentReceipts.observe(world.player);
                check(events.isEmpty(), "槽位变空但打空事件未到达时必须保持沉默");
                EquipmentReceipts.entityEvent(world.player, event(world.player, (byte) 47));
                var broken = events.stream().filter(EquipmentReceiptsTest::mentionsBroken).toList();
                check(broken.size() == 1, "打空实体事件必须恰好发布一次 item_broken");
                String text = broken.getFirst();
                check(text.contains("\"item_id\":\"minecraft:stone_pickaxe\""), "事件必须按扫描基准点名打空的物品");
                check(text.contains("\"equipment_slot\":\"mainhand\""), "事件必须写明槽位");
                check(text.contains("\"durability_last_seen\":31"), "事件携带最后一次扫描到的剩余耐久");
                check(text.contains("\"damage_observed_total\":40"), "事件累计扫描窗口内观察到的耐久消耗");
                // 同一条消灭事实只报一次：事件已消费基准，重复到达不得再发布。
                EquipmentReceipts.entityEvent(world.player, event(world.player, (byte) 47));
                check(events.stream().filter(EquipmentReceiptsTest::mentionsBroken).count() == 1,
                        "重复的打空事件不得重复发布");
            }
        }
    }

    private static void entityEventIgnoresStrangersAndNonBreakEvents() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var events = new ArrayList<String>();
            try (var ignored = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                world.level.entities.put(world.player.getId(), world.player);
                world.player.setItemSlot(EquipmentSlot.MAINHAND, pickaxe(100));
                EquipmentReceipts.observe(world.player);
                var stranger = world.h.allocate(Stranger.class);
                ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "level").set(stranger, world.level);
                stranger.setId(world.player.getId() + 7);
                world.level.entities.put(stranger.getId(), stranger);
                EquipmentReceipts.entityEvent(world.player, event(stranger, (byte) 47));
                check(events.isEmpty(), "其他实体的打空事件不得发布，也不得清掉本地基准");
                EquipmentReceipts.entityEvent(world.player, event(world.player, (byte) 60));
                check(events.isEmpty(), "非打空编号的实体事件必须被忽略");
                // 陌生事件消耗过后本地打空照常发布，基准不被旁路事实污染。
                world.player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                EquipmentReceipts.observe(world.player);
                EquipmentReceipts.entityEvent(world.player, event(world.player, (byte) 47));
                var broken = events.stream().filter(EquipmentReceiptsTest::mentionsBroken).toList();
                check(broken.size() == 1 && broken.getFirst().contains("\"durability_last_seen\":31"),
                        "陌生与非打空事件后，本玩家的打空事实照常可归因");
            }
        }
    }

    /** 旁路构造器的陌生实体：只用于核实打空事件按实体身份过滤。 */
    private static final class Stranger extends net.minecraft.world.entity.monster.Zombie {
        private Stranger() { super(net.minecraft.world.entity.EntityType.ZOMBIE, null); }
    }

    private static net.minecraft.network.protocol.game.ClientboundEntityEventPacket event(
            net.minecraft.world.entity.Entity entity, byte eventId) {
        return new net.minecraft.network.protocol.game.ClientboundEntityEventPacket(entity, eventId);
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
