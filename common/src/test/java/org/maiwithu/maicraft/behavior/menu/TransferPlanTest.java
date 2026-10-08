// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.TransferPlan.Kind;
import org.maiwithu.maicraft.behavior.menu.TransferPlan.Step;
import org.maiwithu.maicraft.behavior.menu.TransferPlan.Where;

/** 搬运计划：整堆一笔快速移动；尾数拆成二分堆；只有整堆操作允许交换目标格。 */
class TransferPlanTest {

    private static List<Step> stepsOf(TransferPlan plan) {
        return plan.steps();
    }

    @Test
    void 整堆快速移动只有一笔() {
        TransferPlan plan = TransferPlan.quickMoveWholeStack();
        List<Step> steps = stepsOf(plan);
        assertEquals(1, steps.size());
        assertEquals(Kind.QUICK_MOVE, steps.getFirst().kind());
        assertEquals(Where.SOURCE, steps.getFirst().where());
        assertFalse(plan.maySwapTarget(), "快速移动不交换任何格子");
    }

    @Test
    void 整堆搬到指定格允许交换目标格() {
        TransferPlan plan = TransferPlan.swapWholeStack();
        assertEquals(2, plan.steps().size());
        assertEquals(Kind.TAKE_ALL, plan.steps().get(0).kind());
        assertEquals(Kind.GIVE_ALL, plan.steps().get(1).kind());
        assertTrue(plan.maySwapTarget(), "整堆操作是交换目标格的唯一许可");
    }

    @Test
    void 六十四取四十九放进目标格的是三十二十六一() {
        // 64 取 49：放进目标格的依次是 32、16、1，多拿的一个一个退回源格。
        TransferPlan plan = TransferPlan.exactAmount(64, 49, 64);
        assertFalse(plan.maySwapTarget(), "指定数量的搬运不许交换目标格");
        List<Integer> given = plan.steps().stream()
                .filter(step -> step.where() == Where.TARGET)
                .map(TransferPlan.Step::items)
                .toList();
        assertEquals(List.of(32, 16, 1), given, "尾数按二分堆放进去");
        // 步骤里包括退回：多拿的 8 个里放 1 个、退 7 个。
        long putBack = plan.steps().stream().filter(step -> step.kind() == Kind.PUT_BACK_ONE).count();
        assertEquals(7, putBack);
    }

    @Test
    void 计划执行完源格剩的正好是差额() {
        // 用计划在数字上走一遍：源格、光标、目标格的数量与预期一致，光标最后为空。
        int sourceCount = 64;
        int amount = 49;
        int source = sourceCount;
        int cursor = 0;
        int deposited = 0;
        for (Step step : TransferPlan.exactAmount(sourceCount, amount, 64).steps()) {
            switch (step.kind()) {
                case TAKE_HALF -> { cursor = (source + 1) / 2; source -= cursor; }
                case GIVE_ALL -> { deposited += cursor; cursor = 0; }
                case GIVE_ONE -> { cursor -= 1; deposited += 1; }
                case PUT_BACK_ONE -> { cursor -= 1; source += 1; }
                case QUICK_MOVE, TAKE_ALL -> throw new IllegalStateException("尾数计划里不该出现整堆步骤");
            }
        }
        assertEquals(sourceCount - amount, source);
        assertEquals(amount, deposited);
        assertEquals(0, cursor);
    }

    @Test
    void 拿一半刚好够用时不拆更多() {
        // 16 取 8：拿半堆正好是要的量，放完就结束。
        TransferPlan plan = TransferPlan.exactAmount(16, 8, 16);
        assertEquals(2, plan.steps().size());
        assertEquals(Kind.TAKE_HALF, plan.steps().get(0).kind());
        assertEquals(Kind.GIVE_ALL, plan.steps().get(1).kind());
    }

    @Test
    void 整堆与非法数量不走尾数计划() {
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(64, 64, 64));
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(64, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(8, 9, 8));
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(64, 10, 32),
                "源格装不回退件数时不给计划");
    }
}
