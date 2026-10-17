// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.HotbarSwapTarget;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;

/**
 * 挖路用的工具只在主背包时挪到快捷栏的打算：寻路引擎挖方块只从快捷栏挑工具，主背包里的镐、原子拆解机它够不着，
 * 就拿手边的扳手、火把硬挖。像玩家一样先打开背包把最顺手的那把换到快捷栏，再接着走。
 *
 * <p>只在主背包里那把比快捷栏里最好的挖得更快（或快捷栏里没有能挖下来的、它能）时才挪；
 * 换到快捷栏哪一格按 {@link HotbarSwapTarget}：空格先于零碎东西，工具、武器、吃的、桶、火把、正要用的不挪走。
 */
final class ToolStaging {

    private static final int HOTBAR_SIZE = 9;
    private static final int LAST_MAIN_SLOT = 35;

    private ToolStaging() {}

    /**
     * 要不要挪、怎么挪。快捷栏里已经有一样好的、主背包也没有更好的、或快捷栏腾不出格子时为空。
     *
     * @param items    背包前 36 格：0~8 快捷栏，9~35 主背包
     * @param selected 选中的快捷栏格
     * @param state    这一两步要挖的方块
     */
    static Optional<ScaffoldStaging.Swap> plan(List<ItemStack> items, int selected, BlockState state, ItemsToKeep keeps) {
        Score hotbarBest = Score.NONE;
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            hotbarBest = hotbarBest.max(Score.of(items.get(slot), state));
        }
        int source = -1;
        Score best = hotbarBest;
        for (int slot = HOTBAR_SIZE; slot < Math.min(items.size(), LAST_MAIN_SLOT + 1); slot++) {
            Score score = Score.of(items.get(slot), state);
            if (score.betterThan(best)) {
                best = score;
                source = slot;
            }
        }
        if (source < 0) return Optional.empty();
        int target = HotbarSwapTarget.pick(HotbarSwapTarget.hotbar(items, keeps), selected);
        if (target < 0) return Optional.empty();
        String itemId = BuiltInRegistries.ITEM.getKey(items.get(source).getItem()).toString();
        return Optional.of(new ScaffoldStaging.Swap(source, target, itemId));
    }

    /** 一样东西挖这个方块有多顺手：挖得下来（掉东西）先于挖不下来，同样挖得下来的比快慢。坏到头的不算。 */
    record Score(boolean drops, float speed) {
        static final Score NONE = new Score(false, 1.0f);

        static Score of(ItemStack stack, BlockState state) {
            if (stack.isEmpty() || stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                return NONE;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed <= 1.0f) return NONE;
            return new Score(!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state), speed);
        }

        boolean betterThan(Score other) {
            if (drops != other.drops) return drops;
            return speed > other.speed;
        }

        Score max(Score other) {
            return other.betterThan(this) ? other : this;
        }
    }
}
