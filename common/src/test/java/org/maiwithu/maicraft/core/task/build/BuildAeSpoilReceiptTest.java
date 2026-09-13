// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskResult;

/** 整理器接收 AE 双侧确认后，仍核对批准数量与背包实际差量；不能多存建材或丢失垫脚储备。 */
public final class BuildAeSpoilReceiptTest {
    private static final ResourceLocation DIRT = ResourceLocation.withDefaultNamespace("dirt");
    private static final ResourceLocation COBBLE = ResourceLocation.withDefaultNamespace("cobblestone");
    private static final Map<ResourceLocation, Integer> LIMITS = Map.of(DIRT, 197, COBBLE, 128);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        verifiedQuantitiesCannotChangeDirectionOrExceedAuthorization();
        actualInventoryMustAgreeBeforeAnyGroupIsCredited(false);
        actualInventoryMustAgreeBeforeAnyGroupIsCredited(true);
        System.out.println("BuildAeSpoilReceiptTest: passed");
    }

    private static void verifiedQuantitiesCannotChangeDirectionOrExceedAuthorization() {
        check(BuildExcavationSpoilSupply.verifiedAeCounts(receipt(197, 128), LIMITS).equals(LIMITS), "完整存入量等于本次批准余料");
        var partial = receipt(64, 0);
        check(BuildExcavationSpoilSupply.verifiedAeCounts(partial, LIMITS).get(DIRT) == 64, "只登记已确认的部分存入量");
        reject(Map.of("operation", "supply", "deposited", Map.of(DIRT.toString(), 197), "confirmed_deposited_total", 197));
        reject(receipt(198, 128));
        reject(Map.of("operation", "deposit", "deposited", Map.of(DIRT.toString(), 1.5), "confirmed_deposited_total", 1.5));
        reject(Map.of("operation", "deposit", "deposited", Map.of("minecraft:diamond", 1), "confirmed_deposited_total", 1));
        reject(Map.of("operation", "deposit", "deposited", Map.of(DIRT.toString(), 64), "confirmed_deposited_total", 65));
        reject(Map.of("operation", "deposit", "deposited", Map.of(DIRT.toString(), -1), "confirmed_deposited_total", -1));
    }

    private static void actualInventoryMustAgreeBeforeAnyGroupIsCredited(boolean diverged) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            // 初始土石与批准存入量之差，分别是三块建材和一组圆石支撑。
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 64)); h.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
            h.inventory.setItem(2, new ItemStack(Items.DIRT, 64)); h.inventory.setItem(3, new ItemStack(Items.DIRT, 8));
            for (int slot = 4; slot < 7; slot++) h.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            var supply = new BuildExcavationSpoilSupply(); supply.begin(h.player, "ae-spoil", 1000, LIMITS, List.of(), 48);
            field("aeLimits").set(supply, LIMITS); field("aeBefore").set(supply, Map.of(DIRT, 200, COBBLE, 192));
            // 模拟会话已经收到网络增加回执；另行写入相应客户端背包观察，以检验整理器的第二层核对。
            for (int slot = 0; slot < 7; slot++) h.inventory.setItem(slot, ItemStack.EMPTY);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, diverged ? 2 : 3));
            h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            var account = BuildExcavationSpoilSupply.class.getDeclaredMethod("accountAe", TaskResult.class); account.setAccessible(true);
            boolean accepted = (boolean) account.invoke(supply, TaskResult.ok("原生 AE 会话回执夹具", receipt(197, 128)));
            check(accepted != diverged, "任一类背包减少过量时，整份新回执都不能记成成功");
            check(supply.receipt().get("outcome_uncertain").equals(diverged), "背包与网络回执不符必须向父任务报告不确定性");
            check(supply.receipt().get("confirmed_deposited").equals(diverged ? Map.of() : Map.of(DIRT.toString(), 197, COBBLE.toString(), 128)),
                    "没有全部核对完就不能部分更新整理账，防止下轮误把珍藏或建材抵掉");
            check(h.blockUses() == 0 && h.itemUses() == 0, "回执验算本身不得触发补偿点击或隐藏库存操作");
        }
    }

    private static Map<String, Object> receipt(int dirt, int cobble) {
        return Map.of("operation", "deposit", "deposited", Map.of(DIRT.toString(), dirt, COBBLE.toString(), cobble),
                "confirmed_deposited_total", dirt + cobble, "outcome_uncertain", false, "effects_started", dirt + cobble > 0);
    }
    private static void reject(Map<String, Object> receipt) {
        try { BuildExcavationSpoilSupply.verifiedAeCounts(receipt, LIMITS); }
        catch (IllegalArgumentException rejected) { return; }
        throw new AssertionError("无法归属于批准余料的 AE 存入必须拒绝");
    }
    private static Field field(String name) throws Exception {
        Field field = BuildExcavationSpoilSupply.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
