// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 不经寻路、在地上朝一点挪几步时怎么按键：朝着目标按，不管镜头此刻朝哪（像人按 W 加 A 斜着挪），
 * 离目标半格以内按得轻，不一步迈过头；前面那一步是岩浆、火就不迈。
 * 实机 10-15 下界门口原先按"镜头朝哪就往前走"：走过了对位点，镜头慢慢转一百八十度的那几秒里一直往西走，
 * 走进门边的岩浆湖烧死。
 */
public final class GroundSteering {

    /** 离目标不到这么远，按键按距离减轻。 */
    private static final double SLOW_WITHIN = 0.5;
    /** 往前看多远落脚的那一格是不是岩浆、火。 */
    private static final double STEP_AHEAD = 0.8;

    private GroundSteering() {
    }

    /** 朝 (dx, dz) 挪的按键，按此刻镜头朝向（偏航）折成前后、左右。 */
    public static PlayerInput.Movement keys(double dx, double dz, float yaw, boolean jump) {
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-4) return new PlayerInput.Movement(0.0f, 0.0f, jump, false, false);
        double scale = Math.min(1.0, length / SLOW_WITHIN) / length;
        double angle = Math.toRadians(yaw);
        float forward = (float) ((-dx * Math.sin(angle) + dz * Math.cos(angle)) * scale);
        float strafe = (float) ((dx * Math.cos(angle) + dz * Math.sin(angle)) * scale);
        return new PlayerInput.Movement(forward, strafe, jump, false, false);
    }

    /**
     * 朝 (dx, dz) 再迈一步落脚的那一格、和它下面一格，是不是岩浆、火这类人不会往里走的。
     *
     * @param exempt 要进的那种方块本身不算（末地门也在危险名单里）
     */
    public static boolean hazardAhead(BlockGetter level, Vec3 at, double dx, double dz, Predicate<BlockState> exempt) {
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-4) return false;
        double reach = Math.min(length, STEP_AHEAD);
        BlockPos feet = BlockPos.containing(at.x + dx / length * reach, at.y, at.z + dz / length * reach);
        return hazard(level, feet, exempt) || hazard(level, feet.below(), exempt);
    }

    private static boolean hazard(BlockGetter level, BlockPos cell, Predicate<BlockState> exempt) {
        return !exempt.test(level.getBlockState(cell)) && BlockPassability.isHazard(level, cell);
    }
}
