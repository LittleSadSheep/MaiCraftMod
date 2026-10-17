// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 搬运计划：一次搬运要点哪几下的纯计算，不含点击本身。
 *
 * <p>整堆走快速移动（一笔点击，目标格由游戏挑）；要指定目标格的整堆先整份拿起再整份放下，
 * 这是唯一允许交换目标格的情形。尾数不能靠快速移动——尾数搬运用快速移动会把整堆多取走——
 * 而是拆成二分堆：右键每次拿走源格向上取整的半堆，凑够要的量就整份放进目标格，
 * 拿多了先一个一个放、放够再把手里剩的退回源格。例如从 64 个里取 49 个，放进目标格的是 32、16、1 三份。
 *
 * <p>执行纪律：每笔点击前核对界面身份（还是认领的那份菜单）与光标（为空才从源格动手）；
 * 指定数量的搬运只允许把目标格往多了堆同一种物品，遇到装不下的物品格不交换、换一格。
 */
public final class TransferPlan {

    /** 点击发生在哪一侧的槽位上。 */
    public enum Where { SOURCE, TARGET }

    /** 一步点击的原版鼠标操作。 */
    public enum Kind {
        /** 快速移动（Shift+左键）整堆走向另一侧，目标格由游戏挑。 */
        QUICK_MOVE,
        /** 光标为空时左键源格：整份拿起。 */
        TAKE_ALL,
        /** 光标为空时右键源格：拿走向上取整的半堆。 */
        TAKE_HALF,
        /** 光标拿着物品时左键目标格：整份放下（与目标格物品相同时并入，不同时是交换）。 */
        GIVE_ALL,
        /** 光标拿着物品时右键目标格：放下一个。 */
        GIVE_ONE,
        /** 光标拿着物品时右键源格：退回一个。 */
        PUT_BACK_ONE
    }

    /** 一步点击：在源格或目标格上做一次原版鼠标操作，items 是这一步移动的物品数。 */
    public record Step(Where where, Kind kind, int items, String note) {
    }

    private final boolean maySwapTarget;
    private final List<Step> steps;

    private TransferPlan(boolean maySwapTarget, List<Step> steps) {
        this.maySwapTarget = maySwapTarget;
        this.steps = steps;
    }

    /** 是否允许交换目标格；只有整堆操作为真，指定数量的搬运永远不许拿目标格上不相干的东西换位。 */
    public boolean maySwapTarget() {
        return maySwapTarget;
    }

    /** 按顺序要做的点击。 */
    public List<Step> steps() {
        return steps;
    }

    /** 整堆快速移动：一笔点击把整堆送到另一侧，不指定落在哪一格，也不交换任何格子。 */
    public static TransferPlan quickMoveWholeStack() {
        return new TransferPlan(false, List.of(
                new Step(Where.SOURCE, Kind.QUICK_MOVE, Integer.MAX_VALUE, "整堆快速移动到另一侧")));
    }

    /** 整堆搬到指定的目标格：整份拿起再整份放下；目标格上是别的东西时允许交换，这是交换的唯一许可。 */
    public static TransferPlan swapWholeStack() {
        return new TransferPlan(true, List.of(
                new Step(Where.SOURCE, Kind.TAKE_ALL, Integer.MAX_VALUE, "整份拿起"),
                new Step(Where.TARGET, Kind.GIVE_ALL, Integer.MAX_VALUE, "整份放进目标格，允许交换")));
    }

    /**
     * 精确数量的尾数搬运：从源格拿走正好 amount 件，放进目标格。
     *
     * @param sourceCount    动手前源格里的件数
     * @param amount         要搬的件数，必须少于源格里的件数（等于或更多时用整堆计划）
     * @param sourceCapacity 源格的容量（这种物品一格最多堆多少），退回多拿的件数时不得超过
     */
    public static TransferPlan exactAmount(int sourceCount, int amount, int sourceCapacity) {
        if (sourceCount < 1 || amount < 1 || amount >= sourceCount) {
            throw new IllegalArgumentException(
                    "尾数搬运要求 1 ≤ 数量 < 源格件数：源格 " + sourceCount + " 件，要搬 " + amount + " 件");
        }
        if (sourceCapacity < sourceCount) {
            throw new IllegalArgumentException("源格容量 " + sourceCapacity + " 装不下现有 " + sourceCount + " 件");
        }
        List<Step> steps = new ArrayList<>();
        int source = sourceCount;
        int cursor = 0;
        int deposited = 0;
        // 反复拿半堆、放整份，直到目标格收满；每次拿半堆都让源格严格变少，循环必然结束。
        while (deposited < amount) {
            int taken = (source + 1) / 2;
            cursor = taken;
            source -= taken;
            steps.add(new Step(Where.SOURCE, Kind.TAKE_HALF, taken, "拿走半堆"));
            int remaining = amount - deposited;
            if (cursor <= remaining) {
                // 手里这堆用得上：整份放进目标格。
                deposited += cursor;
                steps.add(new Step(Where.TARGET, Kind.GIVE_ALL, cursor, "整份放进目标格"));
                cursor = 0;
            } else {
                // 拿多了：先一个一个放，放够要的量，再把手里剩的一个一个退回源格。
                while (deposited < amount) {
                    cursor -= 1;
                    deposited += 1;
                    steps.add(new Step(Where.TARGET, Kind.GIVE_ONE, 1, "放一个"));
                }
                while (cursor > 0) {
                    if (source + 1 > sourceCapacity) {
                        throw new IllegalArgumentException("多拿的件数退不回源格，容量不够");
                    }
                    cursor -= 1;
                    source += 1;
                    steps.add(new Step(Where.SOURCE, Kind.PUT_BACK_ONE, 1, "退回源格一个"));
                }
            }
        }
        return new TransferPlan(false, List.copyOf(steps));
    }
}
