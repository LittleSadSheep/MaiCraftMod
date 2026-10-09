// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 挖方块的生产实现：每要挖一格，就请一套原生挖掘基础代码上场，
 * 瞄准、选工具、持续挖和等确认都在里面，这里只把一次挖掘包成一个可推进的动作。
 *
 * <p>挖的许可（动谁的方块）由采集任务的许可检查先把关，到这里的一格都是获准动土的。
 */
public final class LiveBlockDigging implements DigsBlocks {

    private final Supplier<BlockBreaking> diggings;

    public LiveBlockDigging(Supplier<BlockBreaking> diggings) {
        this.diggings = diggings;
    }

    @Override
    public Optional<Action> dig(BlockPos target) {
        // 挖掘本身就是一个动作：瞄准这一格交给它，推进、被打断停手、收尾结清都由它自己管。
        BlockBreaking digging = diggings.get();
        digging.aimAt(target.immutable());
        return Optional.of(digging);
    }
}
