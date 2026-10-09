// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

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
        return Optional.of(new DigCell(diggings.get(), target.immutable()));
    }

    /** 挖掉一格的动作：推进一刻挖一下，被打断就停手，收尾结清占着的交互与按键。 */
    private static final class DigCell implements Action {

        private final BlockBreaking digging;
        private final BlockPos target;
        /** 最近一次推进拿到的上下文；停手要结清交互，用的就是它。 */
        private TickContext lastTick;

        DigCell(BlockBreaking digging, BlockPos target) {
            this.digging = digging;
            this.target = target;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            lastTick = context;
            return digging.dig(context, target);
        }

        @Override
        public void pause() {
            // 还没推进过就没有占着的交互与按键，不必停。
            if (lastTick != null) digging.stop(lastTick);
        }

        @Override
        public void close() {
            if (lastTick != null) digging.stop(lastTick);
        }

        @Override
        public String describe() {
            return "正在挖 " + target.toShortString();
        }
    }
}
