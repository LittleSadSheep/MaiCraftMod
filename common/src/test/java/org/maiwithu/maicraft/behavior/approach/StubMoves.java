// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 走向站位替身：记住 begin 过的每个目标，step 按脚本吐状态；脚本走完后一直 Done。
 * Done 时把世界替身的角色挪到刚 begin 的格子，模拟真的走到了。
 */
final class StubMoves implements WalksToSpot {

    final List<BlockPos> begun = new ArrayList<>();
    final Deque<ActionStatus> script = new ArrayDeque<>();
    int stops;
    /** 每次真的走到（step 吐出 Done）之后调用，测试用它摆出"走过去时世界变了"的场景。 */
    Runnable onArrive = () -> {};
    private BlockPos heading;
    private final StubWorld world;

    StubMoves(StubWorld world, ActionStatus... steps) {
        this.world = world;
        for (ActionStatus status : steps) script.add(status);
    }

    @Override public void begin(BlockPos feet) {
        begun.add(feet);
        heading = feet;
    }

    @Override public ActionStatus step(TickContext context) {
        ActionStatus status = script.isEmpty() ? ActionStatus.done() : script.poll();
        if (status instanceof ActionStatus.Done && heading != null) {
            world.feet = heading;
            world.grounded = true;
            onArrive.run();
        }
        return status;
    }

    @Override public void stop() {
        stops++;
    }

    /** 测试里把角色挪回原点用。 */
    void teleport(BlockPos at) {
        world.feet = at;
    }

    /** 供断言当前朝向是否为空。 */
    BlockPos heading() {
        return heading;
    }

    /** 测试里造一个眼睛位置与站位对齐的辅助。 */
    static Vec3 eyeAt(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
    }
}
