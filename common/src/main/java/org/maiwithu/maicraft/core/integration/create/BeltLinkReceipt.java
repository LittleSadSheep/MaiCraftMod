// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** 第一次只建立选择标记，第二次才消耗一件连接器；结构和库存必须共同确认，不能只看动画。 */
public record BeltLinkReceipt(BlockPos first, int beforeCount, boolean creative) {
    public NativeConfirmation.Verdict first(int currentCount, BlockPos selected) {
        if (currentCount != beforeCount || selected != null && !first.equals(selected)) return NativeConfirmation.Verdict.DIVERGED;
        return first.equals(selected) ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
    }
    public NativeConfirmation.Verdict second(int currentCount, BlockPos selected, boolean linked) {
        if (selected != null && !first.equals(selected))
            return NativeConfirmation.Verdict.DIVERGED;
        return consumedOne(beforeCount, currentCount, creative, selected == null && linked);
    }
    public static NativeConfirmation.Verdict consumedOne(int before, int now, boolean creative, boolean changed) {
        // 连接器或补轴都必须同时确认原生结构变化与单件材料守恒，创造模式沿用零消耗语义。
        int expected = before - (creative ? 0 : 1);
        if (now != before && now != expected) return NativeConfirmation.Verdict.DIVERGED;
        return now == expected && changed ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
    }
}
