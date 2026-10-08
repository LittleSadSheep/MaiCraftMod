// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 交互动作测试的全套替身：按脚本回答的角色上下文、转头输入、第一人称现场与按住投影。
 * 现场的视线默认跟着最后一次转头请求走，模拟"镜头转向刚指的方向"，测试不用手算角度。
 */
final class InteractionTestFakes {

    private InteractionTestFakes() {}

    /** 角色上下文替身：刻号、有效性与交互提交入口都是可写的字段。 */
    static final class FakeContext implements PlayerContext {
        long tick;
        boolean current = true;
        boolean canInteract = true;
        InteractionSender sender;
        final FakeInput input = new FakeInput();
        final LocalPlayer player = null;

        FakeContext withSender(ScriptedInteractionSender sender) {
            this.sender = sender;
            return this;
        }

        FakeContext advance() {
            tick++;
            return this;
        }

        @Override public LocalPlayer localPlayer() { return player; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() { return input; }
        @Override public InteractionSender interactionSender() { return sender; }
        @Override public MenuActions menuActions() { return null; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return current; }
        @Override public boolean canInteractThisTick() { return canInteract; }
        @Override public boolean tryClaimInteraction() { return canInteractThisTick(); }

        TickContext asTickContext() {
            return new TickContext() {
                @Override public long gameTick() { return tick; }
                @Override public PlayerContext player() { return FakeContext.this; }
            };
        }
    }

    /** 转头与停步输入替身：记录请求，不写真实玩家。 */
    static final class FakeInput implements PlayerInput {
        final List<Vec3> lookRequests = new ArrayList<>();
        int haltCalls;

        @Override public boolean automationOwnsControls() { return true; }
        @Override public void applyMovement(Movement movement, long leaseTickRevision) {}
        @Override public void requestLook(float yaw, float pitch, long leaseTickRevision) {}
        @Override public void clearLook() {}
        @Override public void releaseAll() {}
        @Override public void lookAt(LocalPlayer player, Vec3 point) { lookRequests.add(point); }
        @Override public void halt(LocalPlayer player) { haltCalls++; }
    }

    /** 第一人称现场替身：视线跟着最后一次转头请求走；方块状态默认未加载（读不到）。 */
    static final class FakeScene implements FirstPersonScene {
        final FakeContext context;
        final Vec3 eye = new Vec3(0.0, 64.0, 0.0);
        HitResult ray;
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 离线测试不触碰真实物品注册表，手持快照默认没有；要测持用场景时再想办法注入。
        ItemStack held;

        FakeScene(FakeContext context) {
            this.context = context;
        }

        /** 视线方向 = 上一次转头请求的方向，模拟"本刻的转头还没落地"；一次都没转过就朝 -Z。 */
        @Override public Vec3 viewVector() {
            List<Vec3> requests = context.input.lookRequests;
            Vec3 aim = requests.size() < 2 ? new Vec3(0.0, 64.0, -1.0) : requests.get(requests.size() - 2);
            Vec3 direction = aim.subtract(eye);
            return direction.lengthSqr() < 1.0e-8 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
        }

        @Override public Vec3 eyePosition() { return eye; }
        @Override public HitResult sightRay() { return ray; }
        /** 看得见的部位；默认每格都从正上方的中心看得见，测"一面都看不到"时把格子放进 hidden。 */
        final Set<BlockPos> hidden = new HashSet<>();
        /** 手里物品按自己射线规则会作用到的格子；测倒水时由测试声明。 */
        final Set<BlockPos> itemPointsAt = new HashSet<>();
        @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) {
            return visibleHit(target.below());
        }
        @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) {
            return itemPointsAt.contains(target);
        }
        @Override public BlockHitResult visibleHit(BlockPos target) {
            return hidden.contains(target) ? null
                    : new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
        }
        @Override public BlockState blockAt(BlockPos pos) { return blocks.get(pos); }
        @Override public boolean isLoaded(BlockPos pos) { return blocks.containsKey(pos); }
        @Override public ItemStack heldItem(InteractionHand hand) { return held; }
    }

    /** 按住投影替身：记录续期与松开，续期永远成功。 */
    static final class RecordingProjection implements UseKeyProjection {
        int renewCalls;
        int releaseCalls;

        @Override public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                                       InteractionHand hand, ItemStack before) {
            renewCalls++;
            return true;
        }

        @Override public void release(Object owner) { releaseCalls++; }
    }
}
