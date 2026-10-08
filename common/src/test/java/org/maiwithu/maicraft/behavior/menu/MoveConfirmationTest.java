// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation.Verdict;

/** 搬运确认：普通槽双侧精确增减；机器槽按源格确切减少加光标空结算；对不上不凑数。 */
class MoveConfirmationTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SlotSnapshot cobble(int count) {
        return SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, count));
    }

    private static SlotSnapshot stone(int count) {
        return SlotSnapshot.of(new ItemStack(Items.STONE, count));
    }

    @Test
    void 双侧精确增减才算结算() {
        Verdict verdict = MoveConfirmation.normal(cobble(64), SlotSnapshot.empty(), cobble(15), cobble(49), 49);
        assertEquals(Verdict.CONFIRMED, verdict);
    }

    @Test
    void 搬空源格与并入已有目标格都算结算() {
        // 源格被搬空后成了空格；目标格原本有 8 个圆石，并入 10 个后是 18 个。
        assertEquals(Verdict.CONFIRMED,
                MoveConfirmation.normal(cobble(10), cobble(8), SlotSnapshot.empty(), cobble(18), 10));
    }

    @Test
    void 两侧都没动是回显还没到() {
        assertEquals(Verdict.WAITING,
                MoveConfirmation.normal(cobble(64), SlotSnapshot.empty(), cobble(64), SlotSnapshot.empty(), 49));
    }

    @Test
    void 只有单侧对上就是对不上() {
        // 目标格对上了、源格没少：别的东西顶了源格。
        assertEquals(Verdict.DIVERGED,
                MoveConfirmation.normal(cobble(64), SlotSnapshot.empty(), cobble(64), cobble(49), 49));
        // 源格少了、目标格多出来的数量不对。
        assertEquals(Verdict.DIVERGED,
                MoveConfirmation.normal(cobble(64), SlotSnapshot.empty(), cobble(14), cobble(10), 49));
        // 进目标格的是另一种东西。
        assertEquals(Verdict.DIVERGED,
                MoveConfirmation.normal(cobble(64), SlotSnapshot.empty(), cobble(15), stone(49), 49));
    }

    @Test
    void 机器槽按源格确切减少加光标空结算() {
        // 燃料放下去立刻被吃：目标格核对不了，源格少了 8、光标为空就是结算。
        assertEquals(Verdict.CONFIRMED,
                MoveConfirmation.machine(cobble(64), cobble(56), true, 8));
        // 光标还拿着东西（放置被原样退回）：等它结清。
        assertEquals(Verdict.WAITING,
                MoveConfirmation.machine(cobble(64), cobble(56), false, 8));
        // 源格没动：等。
        assertEquals(Verdict.WAITING,
                MoveConfirmation.machine(cobble(64), cobble(64), true, 8));
        // 源格少得不对（被溜槽多抽走）：对不上。
        assertEquals(Verdict.DIVERGED,
                MoveConfirmation.machine(cobble(64), cobble(50), true, 8));
    }

    @Test
    void 快照冻结动手前的那一刻() {
        ItemStack stack = new ItemStack(Blocks.COBBLESTONE_STAIRS.asItem(), 12);
        SlotSnapshot before = SlotSnapshot.of(stack);
        stack.setCount(3);
        // 之后的本地预测改不到快照，结算判定读的还是动手前的样子。
        assertEquals(12, before.count());
        assertNotEquals(SlotSnapshot.of(stack), before);
        assertTrue(before.sameIdentity(SlotSnapshot.of(new ItemStack(Items.COBBLESTONE_STAIRS, 5))),
                "同一种物品同一副组件就是同一身份");
        assertFalse(before.sameIdentity(cobble(12)));
    }

    @Test
    void 数量必须为正() {
        SlotSnapshot a = cobble(10);
        assertThrows(IllegalArgumentException.class,
                () -> MoveConfirmation.normal(a, SlotSnapshot.empty(), a, SlotSnapshot.empty(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> MoveConfirmation.machine(a, a, true, -1));
    }
}
