// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的拆卸工具，登记表给它包的一层：模组停用后不再认领方块（清障就照原来的挖），动作给不出；
 * 碰到模组接口对不上也在这里收住，交出的动作同样包一层，推进到一半停用按不支持收场。
 */
public final class CompatDismantleTool implements DismantleTool {

    private final CompatModule module;
    private final DismantleTool tool;

    public CompatDismantleTool(CompatModule module, DismantleTool tool) {
        this.module = Objects.requireNonNull(module, "module");
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    @Override public Optional<Item> toolFor(BlockState state) {
        return ask(() -> tool.toolFor(state), Optional.empty());
    }

    @Override public Optional<Action> dismantle(BlockPos cell) {
        return ask(() -> tool.dismantle(cell), Optional.<Action>empty()).map(action -> new CompatAction(module, action));
    }

    // 读一样东西：停用了或对不上时给"用不了"时的那个答案。
    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }
}
