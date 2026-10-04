package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;

/** 原生轮座槽位变动可确认安装；同件轮胎、缺失同步及组件不同的替换分别保留真实结论。 */
public final class WheelMountUseTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var part = new ItemStack(Items.DIAMOND_PICKAXE);
        check(WheelMountUse.changed(ItemStack.EMPTY, part) == Verdict.APPLIED, "installation changes an empty mount");
        check(WheelMountUse.changed(part, ItemStack.EMPTY) == Verdict.APPLIED, "removal empties the mount");
        check(WheelMountUse.changed(part, part.copy()) == Verdict.PENDING, "existing tire cannot confirm a new click");
        check(WheelMountUse.changed(ItemStack.EMPTY, null) == Verdict.PENDING, "missing observation stays unknown");
        // 同种物品若组件不同仍是一次可观察替换，不能只按注册名和数量去重。
        var replacement = part.copy(); replacement.set(DataComponents.DAMAGE, 1);
        check(WheelMountUse.changed(part, replacement) == Verdict.APPLIED, "component changes confirm replacement");
        System.out.println("WheelMountUseTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
