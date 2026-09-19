// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 原料已经转化或暂停恢复时，仍可读取此前真实投料的冻结回执；测试不创建世界，也不要求原实体继续存在。 */
public final class WorldProcessDropReceiptTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ItemStack input = new ItemStack(Items.BOOK); input.set(DataComponents.CUSTOM_NAME, Component.literal("本批原料"));
        UUID entity = UUID.randomUUID(); var row = row(entity, input.copyWithCount(2), 1);
        var credits = WorldProcessDropReceipt.read(Map.of("received_entities", List.of(row)), input, RegistryAccess.EMPTY);
        check(credits.size() == 1 && credits.getFirst().uuid().equals(entity) && credits.getFirst().amount() == 1,
                "合堆后的两件不能冒充本次投出了两件，暂停恢复也不回查已经被原版消耗的实体");
        rejects(() -> WorldProcessDropReceipt.read(Map.of("received_entities", List.of(row(entity, new ItemStack(Items.BOOK), 1))), input, RegistryAccess.EMPTY));
        rejects(() -> WorldProcessDropReceipt.read(Map.of("received_entities", List.of(row(entity, input, 0))), input, RegistryAccess.EMPTY));
        rejects(() -> WorldProcessDropReceipt.read(Map.of("received_entities", List.of(row(entity, input, 2))), input, RegistryAccess.EMPTY));
        check(input.getCount() == 1 && input.has(DataComponents.CUSTOM_NAME), "读回执不能更改输入物品");
        System.out.println("WorldProcessDropReceiptTest: frozen UUID credits, exact increments and component guards passed");
    }
    private static Map<String, Object> row(UUID id, ItemStack observed, int amount) {
        var out = new LinkedHashMap<String, Object>(); out.put("entity_uuid", id.toString()); out.put("entity_id", 21); out.put("count", amount);
        out.put("stack", ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.EMPTY), observed).getOrThrow()); return out;
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalStateException rejected) { return; }
        throw new AssertionError("不完整或不符的原生投料证据应被拒绝");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
