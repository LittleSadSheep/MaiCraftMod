// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.EndermanGaze;
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
        move(tick, faceEntityId, step, false);
    }

    /**
     * 同 {@link #move}，但这一眼会对上没惹毛的末影人的眼睛时把镜头往下压看脚前（走的方向不变）；
     * 站着不动、没有要看的也查一眼此刻的镜头。打龙时末地到处是末影人，平视、看天上的龙都可能正好对上一只。
     */
    public static void moveAvertingGaze(TickContext tick, int faceEntityId, Footwork.Step step) {
        move(tick, faceEntityId, step, true);
    }

    private static void move(TickContext tick, int faceEntityId, Footwork.Step step, boolean avertGaze) {
        PlayerContext player = tick.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null) return;
        if (faceEntityId == KEEP_AIM) {
            // 不动镜头：按此刻实际朝向把世界方向折成前后左右，镜头留给出手的瞄点。
            double radians = Math.toRadians(self.getYRot());
            float forward = (float) Mth.clamp(-step.dx() * Math.sin(radians) + step.dz() * Math.cos(radians), -1, 1);
            float strafe = (float) Mth.clamp(step.dx() * Math.cos(radians) + step.dz() * Math.sin(radians), -1, 1);
            player.input().applyMovement(new PlayerInput.Movement(forward, strafe, step.jump(), false, false),
                    player.clientTick());
            return;
        }
        Entity target = faceEntityId < 0 ? null : self.level().getEntity(faceEntityId);
        float yaw;
        float pitch;
        if (step.sprint() && !step.standing() || target == null) {
            if (step.standing()) {
                player.input().applyMovement(new PlayerInput.Movement(0f, 0f, false, false, false), player.clientTick());
                // 站着不动、没有要看的：此刻的镜头对上末影人就往下压。
                if (avertGaze) {
                    float averted = avertedPitch(self, self.getYRot(), self.getXRot());
                    if (averted != self.getXRot()) player.input().requestLook(self.getYRot(), averted, player.clientTick());
                }
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
        if (avertGaze) pitch = avertedPitch(self, yaw, pitch);
        player.input().requestLook(yaw, pitch, player.clientTick());
        // 朝向 yaw 时，前方是 (-sin, cos)，左方是 (cos, sin)：世界方向投影到这两条上就是前后与左右的按键量。
        double radians = Math.toRadians(yaw);
        float forward = (float) Mth.clamp(-step.dx() * Math.sin(radians) + step.dz() * Math.cos(radians), -1, 1);
        float strafe = (float) Mth.clamp(step.dx() * Math.cos(radians) + step.dz() * Math.sin(radians), -1, 1);
        player.input().applyMovement(new PlayerInput.Movement(forward, strafe, step.jump(), false,
                step.sprint() && forward > 0.8f), player.clientTick());
    }

    /** 迈步时不动镜头（手上正瞄着出手）。 */
    public static final int KEEP_AIM = -2;

    /** 看末影人眼睛的范围（格）：原版 64 格内的末影人会因为对视发怒。 */
    private static final double GAZE_RANGE = EndermanGaze.RANGE;

    // 不惹毛附近没惹毛的末影人的俯仰角：看得见它的眼睛的才算；戴着雕刻南瓜不会对视。
    private static float avertedPitch(LocalPlayer self, float yaw, float pitch) {
        List<EndermanGaze.Eyes> eyes = new ArrayList<>();
        for (EnderMan enderman : self.level().getEntitiesOfClass(EnderMan.class,
                self.getBoundingBox().inflate(GAZE_RANGE), candidate -> candidate.isAlive() && !candidate.isCreepy())) {
            eyes.add(new EndermanGaze.Eyes(enderman.getX(), enderman.getEyeY(), enderman.getZ(),
                    self.hasLineOfSight(enderman)));
        }
        if (eyes.isEmpty()) return pitch;
        return EndermanGaze.avertedPitch(self.getEyePosition(), yaw, pitch, eyes,
                self.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN));
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
