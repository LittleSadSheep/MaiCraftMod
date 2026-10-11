// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走位那一步的生产实现：读当刻脚下的路给走位挑方向，再按挑好的一步迈。落脚处脚下两格内没有能站的地面（悬崖、深坑）、
 * 是岩浆火堆仙人掌浆果丛这类伤人的、或者那一格被方块挡住，就不往那儿迈。后退、横移时眼睛还看着它，被追上也照样出手。
 */
public final class CombatStepping {

    /** 看多远之外的落脚处（格）：一刻走不出这么远，看一步就够。 */
    private static final double LOOK_AHEAD = 0.8;
    /** 脚下往下最多看几格找地面：再深就是会摔的坑。 */
    private static final int SAFE_DROP = 2;

    private CombatStepping() {}

    /**
     * 当刻脚下的路，供走位挑方向：朝那个方向看一步远的落脚处，站得住是平地；那一格被挡、但上面两格空着、
     * 头顶也空着能起跳，是一格高的台阶要跳；斜着走还要看半步处，免得卡在墙角。没接上角色时哪儿都过不去。
     */
    public static Footwork.Ground ground(TickContext tick) {
        PlayerContext player = tick.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null) return (dx, dz) -> Footwork.Footing.BLOCKED;
        Level level = self.level();
        Vec3 at = self.position();
        return (dx, dz) -> {
            BlockPos feet = BlockPos.containing(at.x + dx * LOOK_AHEAD, at.y, at.z + dz * LOOK_AHEAD);
            boolean diagonal = Math.abs(dx) > 0.1 && Math.abs(dz) > 0.1;
            if (diagonal && !passable(level, BlockPos.containing(at.x + dx * LOOK_AHEAD / 2, at.y, at.z + dz * LOOK_AHEAD / 2))) {
                return Footwork.Footing.BLOCKED;
            }
            if (safeFooting(level, feet)) return Footwork.Footing.FLAT;
            BlockPos head = self.blockPosition().above(2);
            if (passable(level, head) && safeFooting(level, feet.above())) return Footwork.Footing.STEP_UP;
            return Footwork.Footing.BLOCKED;
        };
    }

    /**
     * 按挑好的一步迈这一刻。平常面朝要打的那只（够得着就能出手），横移、后退都按相对朝向换成前后左右；
     * 疾跑时转身朝跑的方向（原版只有往前才疾跑得起来），躲爆炸、撤离不回头看。站住时只看着它。
     *
     * @param faceEntityId 要面朝的那只；-1 时朝走的方向
     */
    public static void move(TickContext tick, int faceEntityId, Footwork.Step step) {
        PlayerContext player = tick.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null) return;
        Entity target = faceEntityId < 0 ? null : self.level().getEntity(faceEntityId);
        float yaw;
        float pitch;
        if (step.sprint() && !step.standing() || target == null) {
            if (step.standing()) {
                player.input().applyMovement(new PlayerInput.Movement(0f, 0f, false, false, false), player.clientTick());
                return;
            }
            yaw = (float) Math.toDegrees(Math.atan2(step.dz(), step.dx())) - 90;
            pitch = 0;
        } else {
            Vec3 delta = target.position().subtract(self.position());
            yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
            Vec3 eye = self.getEyePosition();
            Vec3 aim = target.getBoundingBox().getCenter();
            pitch = (float) -Math.toDegrees(Math.atan2(aim.y - eye.y, Math.hypot(aim.x - eye.x, aim.z - eye.z)));
        }
        player.input().requestLook(yaw, pitch, player.clientTick());
        // 朝向 yaw 时，前方是 (-sin, cos)，左方是 (cos, sin)：世界方向投影到这两条上就是前后与左右的按键量。
        double radians = Math.toRadians(yaw);
        float forward = (float) Mth.clamp(-step.dx() * Math.sin(radians) + step.dz() * Math.cos(radians), -1, 1);
        float strafe = (float) Mth.clamp(step.dx() * Math.cos(radians) + step.dz() * Math.sin(radians), -1, 1);
        player.input().applyMovement(new PlayerInput.Movement(forward, strafe, step.jump(), false,
                step.sprint() && forward > 0.8f), player.clientTick());
    }

    // 身子过得去：脚和头那两格都没有碰撞。
    private static boolean passable(Level level, BlockPos feet) {
        return level.isLoaded(feet) && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    // 落脚处站得住：那一格和头那格没被挡，往下两格内有实心的地面，脚下和地面都不伤人。
    static boolean safeFooting(Level level, BlockPos feet) {
        if (!level.isLoaded(feet)) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
            return false;
        }
        if (hurts(level.getBlockState(feet))) return false;
        for (int down = 1; down <= SAFE_DROP + 1; down++) {
            BlockPos below = feet.below(down);
            BlockState state = level.getBlockState(below);
            if (hurts(state)) return false;
            if (!state.getCollisionShape(level, below).isEmpty()) return true;
        }
        return false;
    }

    private static boolean hurts(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.getBlock() instanceof BaseFireBlock || state.is(Blocks.CAMPFIRE);
    }
}
