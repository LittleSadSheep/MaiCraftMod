// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 直线走几步：面朝一点按住前进，走到离它水平不到容差就停住。寻路看不见的地面（电梯轿厢、移动装置、船的甲板）上
 * 走进走出用它：路很短、一眼看得到，像玩家那样直着走过去，不经过寻路。走不到（被挡住、半天没挪近）就停住如实报告。
 */
public final class StraightWalk implements Action {

    /** 这么多刻没挪近一点就当被挡住。 */
    private static final int STALL_TICKS = 30;
    private static final double MIN_GAIN = 0.05;

    private final Supplier<PlayerContext> context;
    private final Vec3 target;
    private final double tolerance;
    private final int maxTicks;
    private final String what;
    /** 目标比脚下高半格以上时跳一下（走上一格高的甲板边）。 */
    private final boolean climb;
    private int ticks;
    private int stalled;
    private double best = Double.MAX_VALUE;

    /**
     * @param target    要走到的点（世界坐标，只比水平）
     * @param tolerance 水平离它多近算到（格）
     * @param maxTicks  最多走多少刻
     * @param what      给面板与问题的一句话，例如"走进轿厢"
     */
    public StraightWalk(Supplier<PlayerContext> context, Vec3 target, double tolerance, int maxTicks, String what) {
        this(context, target, tolerance, maxTicks, what, false);
    }

    /**
     * @param climb 目标比脚下高半格以上、又离得近时跳一下：从地面走上一格高的甲板边用
     */
    public StraightWalk(Supplier<PlayerContext> context, Vec3 target, double tolerance, int maxTicks, String what,
            boolean climb) {
        this.climb = climb;
        this.context = Objects.requireNonNull(context, "context");
        this.target = Objects.requireNonNull(target, "target");
        this.tolerance = tolerance;
        this.maxTicks = maxTicks;
        this.what = Objects.requireNonNull(what, "what");
    }

    // 每刻：到了就停住；没到就看向那一点（眼睛平视）按住前进；半天没挪近或超时就停住报告。
    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext current = context.get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null || current.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，" + what + "走不了"));
        }
        PlayerInput input = current.input();
        double distance = horizontal(player.position());
        if (distance <= tolerance) {
            input.halt(player);
            return ActionStatus.done();
        }
        if (++ticks > maxTicks || stalled > STALL_TICKS) {
            input.halt(player);
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, what + "没走到：还差 "
                    + String.format("%.1f", distance) + " 格，" + (ticks > maxTicks ? "走了 " + maxTicks + " 刻" : "前面被挡住")));
        }
        boolean gained = distance < best - MIN_GAIN;
        if (gained) {
            best = distance;
            stalled = 0;
        } else {
            stalled++;
        }
        input.lookAt(player, new Vec3(target.x, player.getEyeY(), target.z));
        boolean jump = climb && target.y - player.getY() > 0.55 && distance < 1.6 && player.onGround();
        // 按键用本刻的客户端刻号，不用世界时间（两者不同，传错了输入层判过期抛错）。
        input.applyMovement(new PlayerInput.Movement(1.0f, 0.0f, jump, false, false), current.clientTick());
        return gained ? ActionStatus.progressed() : ActionStatus.running();
    }

    private double horizontal(Vec3 at) {
        double dx = target.x - at.x;
        double dz = target.z - at.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override public void pause() {
        release();
    }

    @Override public void close() {
        release();
    }

    private void release() {
        PlayerContext current = context.get();
        if (current != null && current.input() != null && current.localPlayer() != null) {
            current.input().halt(current.localPlayer());
        }
    }

    @Override public Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    @Override public String describe() {
        return what;
    }
}
