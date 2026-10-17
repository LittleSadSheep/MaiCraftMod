// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

/**
 * 搬运确认：一笔搬运有没有结算的两种核对模式，纯函数。
 *
 * <p>普通槽按双侧精确增减核对：源格恰好少了要搬的量、目标格恰好多了同样的量，两边都对上才算数；
 * 一边对一边不对就是现场出了岔子，宁可承认对不上，也不把对不上的一侧凑成数。
 *
 * <p>机器槽（熔炉的燃料槽、被溜槽抽着的格子）按"源格确切减少 + 光标空"结算：
 * 放下去的物品可能在放置回显到达之前就被机器吃掉，目标格核对不了，源格的减少与空光标就是证据。
 */
public final class MoveConfirmation {

    /** 核对结论：结算了 / 还没到 / 对不上。 */
    public enum Verdict { CONFIRMED, WAITING, DIVERGED }

    private MoveConfirmation() {}

    /**
     * 普通槽：源格恰好减少 amount、目标格恰好增加 amount 同一种物品。
     * 两侧都纹丝不动时是回显还没到，继续等；只有一侧变了或变得不对就是对不上。
     *
     * @param sourceBefore 动手前源格的样子
     * @param targetBefore 动手前目标格的样子
     * @param sourceNow    此刻源格的样子
     * @param targetNow    此刻目标格的样子
     * @param amount       这一笔要搬的件数
     */
    public static Verdict normal(SlotSnapshot sourceBefore, SlotSnapshot targetBefore,
                                 SlotSnapshot sourceNow, SlotSnapshot targetNow, int amount) {
        requireAmount(amount);
        boolean sourceOk = decreasedExactly(sourceBefore, sourceNow, amount);
        boolean targetOk = increasedExactly(targetBefore, targetNow, sourceBefore, amount);
        if (sourceOk && targetOk) return Verdict.CONFIRMED;
        boolean untouched = sourceNow.sameIdentity(sourceBefore) && sourceNow.count() == sourceBefore.count()
                && targetNow.sameIdentity(targetBefore) && targetNow.count() == targetBefore.count();
        // 两侧都没动：同步还没到，再等；动了但没对上：承认对不上，不凑数。
        return untouched ? Verdict.WAITING : Verdict.DIVERGED;
    }

    /**
     * 机器槽：源格恰好减少 amount、光标为空，就算结算；
     * 目标格不核对——机器可能在回显到达前就把物品吃掉或转走。
     *
     * @param sourceBefore 动手前源格的样子
     * @param sourceNow    此刻源格的样子
     * @param cursorEmpty  光标上是否已没有物品
     * @param amount       这一笔要搬的件数
     */
    public static Verdict machine(SlotSnapshot sourceBefore, SlotSnapshot sourceNow,
                                  boolean cursorEmpty, int amount) {
        requireAmount(amount);
        if (decreasedExactly(sourceBefore, sourceNow, amount)) {
            // 光标还拿着东西：可能是放置被原样退回，等它结清再下结论。
            return cursorEmpty ? Verdict.CONFIRMED : Verdict.WAITING;
        }
        boolean untouched = sourceNow.sameIdentity(sourceBefore) && sourceNow.count() == sourceBefore.count();
        return untouched ? Verdict.WAITING : Verdict.DIVERGED;
    }

    // 源格结算：同一种物品恰好少了 amount 件；整堆搬空后源格成了空格，也算恰好减少。
    private static boolean decreasedExactly(SlotSnapshot before, SlotSnapshot now, int amount) {
        if (now.isEmpty()) return !before.isEmpty() && before.count() == amount;
        if (!now.sameIdentity(before)) return false;
        return now.count() == before.count() - amount;
    }

    // 目标格结算：同一种物品恰好多了 amount 件；目标格原来是空的时候，进去的应该正是源格那种物品。
    private static boolean increasedExactly(SlotSnapshot before, SlotSnapshot now,
                                            SlotSnapshot sourceBefore, int amount) {
        if (before.isEmpty()) {
            return now.sameIdentity(sourceBefore) && now.count() == amount;
        }
        return now.sameIdentity(before) && now.count() == before.count() + amount;
    }

    private static void requireAmount(int amount) {
        if (amount < 1) throw new IllegalArgumentException("搬运的件数必须为正：" + amount);
    }
}
