// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.List;
import com.mojang.serialization.Codec;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 模拟终端在交换确认窗口内同步电量，验证只放宽已声明的动态字段，不吞掉换物或绑定变化。 */
public final class Ae2TerminalIdentityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        DataComponentType<Double> energy = DataComponentType.<Double>builder().persistent(Codec.DOUBLE).build();
        try (var h = new InteractionWorldTestHarness()) {
            // 无可选模组的夹具用指南针承载瞬时电量，确认算法仍操作真实背包的两个槽位。
            ItemStack terminal = new ItemStack(Items.COMPASS), displaced = new ItemStack(Items.TORCH, 12);
            terminal.set(energy, 100.0); terminal.set(DataComponents.CUSTOM_NAME, Component.literal("network-A"));
            var exact = MenuConfirmation.inventorySwap(9, 0, displaced, terminal);
            var dynamic = MenuConfirmation.inventorySwap(9, 0, displaced, terminal,
                    (actual, expected) -> Ae2TerminalIdentity.sameExceptEnergy(actual, expected, energy));
            h.inventory.setItem(9, terminal.copy()); h.inventory.setItem(0, displaced.copy());
            h.inventory.getItem(9).set(energy, 99.0);
            var context = ClientRuntime.requireContext(h.player);
            check(exact.observe(context, null) == MenuConfirmation.Verdict.DIVERGED, "旧精确交换会把原生耗电误判为替换");
            check(dynamic.observe(context, null) == MenuConfirmation.Verdict.APPLIED, "电量变化不妨碍确认已经交换的两个栏位");
            check(!Ae2TerminalIdentity.same(h.inventory.getItem(9), terminal), "非无线物品仍严格比较全部组件");
            h.inventory.getItem(9).set(DataComponents.CUSTOM_NAME, Component.literal("network-B"));
            check(dynamic.observe(context, null) == MenuConfirmation.Verdict.DIVERGED, "另一份绑定身份不能冒充旧终端");
            h.inventory.setItem(9, terminal.copyWithCount(2));
            check(dynamic.observe(context, null) == MenuConfirmation.Verdict.DIVERGED, "同种物品数量变化也必须报告偏离");
            h.inventory.setItem(9, displaced.copy()); h.inventory.setItem(0, terminal.copy());
            check(dynamic.observe(context, null) == MenuConfirmation.Verdict.NOT_APPLIED, "未交换时不能因两种物品仍存在就判成功");
        }
        var failed = new Ae2ResourceSupply.Outcome(Ae2ResourceSupply.Status.UNCERTAIN, "inventory_restore_unconfirmed",
                "CLICK/DIVERGED: slots changed", List.of(), Ae2ResourceSupply.Operation.OBSERVE,
                false, 0, 0, true, true, "wireless", List.of());
        check(failed.data().get("detail").equals(failed.message()) && Boolean.TRUE.equals(failed.data().get("outcome_uncertain")),
                "父任务收到具体恢复原因，不能只看到不明缺料");
        System.out.println("Ae2TerminalIdentityTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
