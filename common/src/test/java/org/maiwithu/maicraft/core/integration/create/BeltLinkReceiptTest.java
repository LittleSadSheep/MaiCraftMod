// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;

/** 注入独立的结构、标记和库存事实，核对安装回执不会把半次动作记成成功。 */
public final class BeltLinkReceiptTest {
    public static void main(String[] args) {
        BlockPos shaft = new BlockPos(1, 2, 3); var survival = new BeltLinkReceipt(shaft, 2, false);
        check(survival.first(2, null) == Verdict.PENDING, "unacknowledged first click stays pending");
        check(survival.first(2, shaft) == Verdict.APPLIED, "first click consumes nothing and selects the exact shaft");
        check(survival.first(1, shaft) == Verdict.DIVERGED, "first click must not consume material");
        check(survival.first(2, shaft.east()) == Verdict.DIVERGED, "a foreign selection is never adopted");
        check(survival.second(2, null, true) == Verdict.PENDING, "visible geometry without material acknowledgement is insufficient");
        check(survival.second(1, null, false) == Verdict.PENDING, "consumption alone is not proof that the full belt exists");
        check(survival.second(1, shaft, true) == Verdict.PENDING, "remaining selection means the transaction has not settled");
        check(survival.second(1, null, true) == Verdict.APPLIED, "exact one-item consumption plus complete chain settles the link");
        check(survival.second(0, null, true) == Verdict.DIVERGED, "over-consumption never becomes a successful link");
        check(new BeltLinkReceipt(shaft, 1, false).second(0, null, true) == Verdict.APPLIED, "the last connector may become an empty hand");
        var creative = new BeltLinkReceipt(shaft, 1, true);
        check(creative.second(1, null, true) == Verdict.APPLIED && creative.second(0, null, true) == Verdict.DIVERGED,
                "creative mode still requires material preservation and native geometry");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
