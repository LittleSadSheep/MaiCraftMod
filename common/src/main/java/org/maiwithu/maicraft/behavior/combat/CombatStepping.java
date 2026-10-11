// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
 * 近身走位那一步的生产实现：面朝目标，往前凑或往后退一刻。迈之前先看那一步落脚处：
 * 脚下两格内没有能站的地面（悬崖、深坑）、是岩浆火堆仙人掌浆果丛这类伤人的、或者那一格被方块挡住，就站住不迈。
 * 后退时眼睛还看着它，被追上也照样出手。
 */
public final class CombatStepping {

    /** 看多远之外的落脚处（格）：一刻走不出这么远，看一步就够。 */
    private static final double LOOK_AHEAD = 0.8;
    /** 脚下往下最多看几格找地面：再深就是会摔的坑。 */
    private static final int SAFE_DROP = 2;

    private CombatStepping() {}

    /** 面朝这只，往前（正）或往后（负）迈这一刻；0 或那一步不安全时站住。 */
    public static void step(TickContext tick, int entityId, float forward) {
        PlayerContext player = tick.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        Entity target = self == null ? null : self.level().getEntity(entityId);
        if (target == null) return;
        Vec3 delta = target.position().subtract(self.position());
        double flat = Math.hypot(delta.x, delta.z);
        if (flat < 1.0E-3) return;
        Vec3 toward = new Vec3(delta.x / flat, 0, delta.z / flat);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        Vec3 eye = self.getEyePosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y - eye.y, Math.hypot(aim.x - eye.x, aim.z - eye.z)));
        player.input().requestLook(yaw, pitch, player.clientTick());
        float move = forward;
        if (move != 0f) {
            Vec3 next = self.position().add(toward.scale(Math.signum(move) * LOOK_AHEAD));
            if (!safeFooting(self.level(), BlockPos.containing(next))) move = 0f;
        }
        player.input().applyMovement(new PlayerInput.Movement(move, 0f, false, false, false), player.clientTick());
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
