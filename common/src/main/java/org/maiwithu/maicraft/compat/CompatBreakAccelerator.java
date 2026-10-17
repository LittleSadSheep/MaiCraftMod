// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 联动模组交来的挖掘加速，登记表给它包的一层：模组停用后开不出连锁（调用方逐格挖）；
 * 开出来的连锁键也包一层，挖到一半模组停用或接口对不上时答"不连锁、只挖这一格""键已松开"，
 * 让挖掘照常收尾，不变成任务的内部错误。
 */
public final class CompatBreakAccelerator implements BreakAccelerator {

    private final CompatModule module;
    private final BreakAccelerator accelerator;

    public CompatBreakAccelerator(CompatModule module, BreakAccelerator accelerator) {
        this.module = Objects.requireNonNull(module, "module");
        this.accelerator = Objects.requireNonNull(accelerator, "accelerator");
    }

    @Override public Optional<ChainKeyHold> hold(ChainShape shape, Predicate<BlockPos> mayBreak) {
        return ask(module, () -> accelerator.hold(shape, mayBreak), Optional.<ChainKeyHold>empty())
                .map(hold -> new Guarded(module, hold));
    }

    // 读一样东西：停用了或对不上时给"用不了"时的那个答案。
    private static <T> T ask(CompatModule module, Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }

    /** 包一层的连锁键：停用后第一下之前答只挖这一格，挖着的答键丢了，松键答已松开。 */
    private record Guarded(CompatModule module, ChainKeyHold hold) implements ChainKeyHold {

        @Override public Readiness ready(PlayerContext context, BlockHitResult hit) {
            return ask(module, () -> hold.ready(context, hit),
                    new Readiness.Single("连锁挖的联动停用了：" + module.disabledReason().orElse("接口对不上")));
        }

        @Override public boolean keep(PlayerContext context) {
            return ask(module, () -> hold.keep(context), false);
        }

        @Override public boolean release(PlayerContext context) {
            return ask(module, () -> hold.release(context), true);
        }
    }
}
