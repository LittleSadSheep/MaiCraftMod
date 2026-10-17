// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.GroundSteering;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 直接按键踏进危险：寻路会绕开岩浆、不肯迈下悬崖，这几步像玩家那样看着那一格直着走过去。
 *
 * <p>岩浆：走进去就停住站在里面。悬崖：迈出边缘，开始往下掉就松开前进，不放水、不打开鞘翅。
 * 仙人掌：一直朝它推，碰撞箱比整格小一圈，贴着推才持续掉血，不拆它。站进格子（点火、倒岩浆前）：
 * 走到那一格正中站住就结束，格里已经有火也照走。前面被挡住、半天挪不近就如实说迈不进去。
 */
final class HazardStep implements Action {

    /** 这么多刻没挪近一点就当被挡住。 */
    private static final int STALL_TICKS = 60;
    private static final double MIN_GAIN = 0.05;
    /** 站进格子时离正中多近算站好了（格）。 */
    private static final double CENTERED = 0.2;

    private final Supplier<PlayerContext> context;
    /** 死法；只是站进格子时为 null。 */
    private final WayToDie way;
    private final BlockPos stand;
    private final BlockPos entry;
    private final Vec3 target;
    private int stalled;
    private double best = Double.MAX_VALUE;

    /**
     * @param way   踏进哪种危险；只是站进 entry 那一格时为 null
     * @param stand 出发的站位（判断是不是开始往下掉了）
     * @param entry 要进去、贴上去的那一格
     */
    HazardStep(Supplier<PlayerContext> context, WayToDie way, BlockPos stand, BlockPos entry) {
        this.context = Objects.requireNonNull(context, "context");
        this.way = way;
        this.stand = stand.immutable();
        this.entry = entry.immutable();
        this.target = Vec3.atBottomCenterOf(entry);
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext current = context.get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null || current.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，迈不进去"));
        }
        PlayerInput input = current.input();
        double distance = horizontal(player.position());
        if (way == null && distance <= CENTERED && player.blockPosition().equals(entry)) {
            input.halt(player);
            return ActionStatus.done();
        }
        // 已经在危险里：岩浆里站着不动，开始往下掉就松开前进，让它自己掉下去。
        if (way == WayToDie.LAVA && player.isInLava()
                || way == WayToDie.FALL && !player.onGround() && player.getY() < stand.getY() - 0.5) {
            input.halt(player);
            return ActionStatus.running();
        }
        boolean gained = distance < best - MIN_GAIN;
        if (gained) {
            best = distance;
            stalled = 0;
        } else if (way != WayToDie.CACTUS && ++stalled > STALL_TICKS) {
            // 贴着仙人掌推本来就挪不近，不算被挡住。
            input.halt(player);
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "前面被挡住，迈不进去：还差 " + String.format("%.1f", distance) + " 格"));
        }
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        input.lookAt(player, new Vec3(target.x, player.getEyeY(), target.z));
        // 朝着那一格按键（不经寻路，不避岩浆与火）；按键用本刻的客户端刻号。
        input.applySteering(yaw -> GroundSteering.keys(dx, dz, yaw, false), player.getYRot(), current.clientTick());
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

    @Override public String describe() {
        return way == null ? "站进 " + entry.toShortString() : "踏进" + way.label;
    }
}
