// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;

import org.maiwithu.maicraft.behavior.menu.MoveConfirmation;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation.Verdict;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 取货确认：从终端取的一笔有没有算数，纯函数。确认的依据只看角色这一侧（背包格子与光标）：
 * 网络里的数会被机器、别的玩家同时改，拿它核对反而对不上。
 *
 * <p>整组取：背包里这种东西多了 1 到一组之间的件数、光标是空的，就算取到了这么多；纹丝不动是回显还没到；
 * 少了、多出一组以上、光标上挂着东西，都是对不上，宁可承认对不上也不凑数。
 * 取一件：光标上同一种东西恰好多一件才算。放下光标：就是光标到格子的一笔搬运，按搬运确认的普通槽规矩核对。
 */
public final class NetworkTakeConfirmation {

    private NetworkTakeConfirmation() {}

    /**
     * 一次核对的结论。
     *
     * @param verdict 结算了 / 还没到 / 对不上
     * @param amount  结算了时进背包（或上了光标）的件数；其余为 0
     */
    public record Result(Verdict verdict, int amount) {
        static Result waiting() {
            return new Result(Verdict.WAITING, 0);
        }

        static Result diverged() {
            return new Result(Verdict.DIVERGED, 0);
        }
    }

    /**
     * 整组取有没有算数。
     *
     * @param sample    取的那种东西（一件的样子）
     * @param before    点之前角色背包那一侧各格的样子
     * @param now       此刻角色背包那一侧各格的样子，与 before 对齐
     * @param cursorNow 此刻光标上的东西
     */
    public static Result stackTaken(SlotSnapshot sample, List<SlotSnapshot> before, List<SlotSnapshot> now,
            SlotSnapshot cursorNow) {
        int gained = countOf(sample, now) - countOf(sample, before);
        if (!cursorNow.isEmpty()) return Result.diverged();
        if (gained == 0) return Result.waiting();
        int stackSize = sample.stack().getMaxStackSize();
        return gained > 0 && gained <= stackSize ? new Result(Verdict.CONFIRMED, gained) : Result.diverged();
    }

    /**
     * 取一件到光标上有没有算数。
     *
     * @param sample       取的那种东西（一件的样子）
     * @param cursorBefore 点之前光标上的东西
     * @param cursorNow    此刻光标上的东西
     */
    public static Result oneTaken(SlotSnapshot sample, SlotSnapshot cursorBefore, SlotSnapshot cursorNow) {
        int before = cursorBefore.isEmpty() ? 0 : cursorBefore.count();
        if (!cursorNow.isEmpty() && cursorNow.sameIdentity(sample) && cursorNow.count() == before + 1) {
            return new Result(Verdict.CONFIRMED, 1);
        }
        boolean untouched = cursorNow.sameIdentity(cursorBefore) && cursorNow.count() == cursorBefore.count();
        return untouched ? Result.waiting() : Result.diverged();
    }

    /**
     * 把光标上的东西放进背包一格有没有算数：光标整个空掉、那一格恰好多了光标上那么多件才算。
     *
     * @param cursorBefore 放之前光标上的东西
     * @param slotBefore   放之前那一格的样子
     * @param cursorNow    此刻光标上的东西
     * @param slotNow      此刻那一格的样子
     */
    public static Result putDown(SlotSnapshot cursorBefore, SlotSnapshot slotBefore, SlotSnapshot cursorNow,
            SlotSnapshot slotNow) {
        if (cursorBefore.isEmpty()) throw new IllegalArgumentException("光标上没有东西，谈不上放下");
        int amount = cursorBefore.count();
        Verdict verdict = MoveConfirmation.normal(cursorBefore, slotBefore, cursorNow, slotNow, amount);
        return verdict == Verdict.CONFIRMED ? new Result(Verdict.CONFIRMED, amount) : new Result(verdict, 0);
    }

    // 角色那一侧这种东西一共几件：同一种物品同一副组件才算。
    private static int countOf(SlotSnapshot sample, List<SlotSnapshot> slots) {
        int total = 0;
        for (SlotSnapshot slot : slots) {
            if (!slot.isEmpty() && slot.sameIdentity(sample)) total += slot.count();
        }
        return total;
    }
}
