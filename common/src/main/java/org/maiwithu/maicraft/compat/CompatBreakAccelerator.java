// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的挖掘加速，登记表给它包的一层：模组停用后问"会一起挖哪些"一律给空表（清障就退回逐格挖），
 * 动作给不出；碰到模组接口对不上也在这里收住，交出的动作同样包一层，推进到一半停用按不支持收场。
 */
public final class CompatBreakAccelerator implements BreakAccelerator {

    private final CompatModule module;
    private final BreakAccelerator accelerator;

    public CompatBreakAccelerator(CompatModule module, BreakAccelerator accelerator) {
        this.module = Objects.requireNonNull(module, "module");
        this.accelerator = Objects.requireNonNull(accelerator, "accelerator");
    }

    @Override public List<BlockPos> wouldBreakWith(BlockPos trigger) {
        return ask(() -> accelerator.wouldBreakWith(trigger), List.of());
    }

    @Override public Optional<Action> breakBatch(BlockPos trigger) {
        return ask(() -> accelerator.breakBatch(trigger), Optional.<Action>empty()).map(action -> new CompatAction(module, action));
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
