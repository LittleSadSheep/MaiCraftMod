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
        int expected = beforeCount - (creative ? 0 : 1);
        if (currentCount != beforeCount && currentCount != expected || selected != null && !first.equals(selected))
            return NativeConfirmation.Verdict.DIVERGED;
        return currentCount == expected && selected == null && linked ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
    }
}
