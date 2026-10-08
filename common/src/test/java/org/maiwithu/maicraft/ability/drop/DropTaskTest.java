// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 丢东西的离线场景：按实际持有限量丢、超量按持有丢出并写清差额；
 * 抛掷没等到确认按没能确认记账，不盲目重试；落点登记进本会话的寻路避让。
 */
class DropTaskTest {

    private static final String COBBLE = "minecraft:cobblestone";

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 背包替身：只有圆石的数量会被改动。 */
    static final class FakeBackpack implements BackpackView {
        int cobble;
        @Override public List<BackpackStack> stacks() {
            return cobble > 0 ? List.of(new BackpackStack(COBBLE, cobble, 64, false, false, false, true))
                    : List.of();
        }
        @Override public int usedSlots() { return cobble > 0 ? 1 : 0; }
        @Override public int totalSlots() { return 36; }
    }

    /** 抛掷提交替身：确认记录按脚本走，确认丢出时主手少掉对应的一格/一件。 */
    static final class FakeDropSender implements InteractionSender {
        // 确认记录的构造与终结由交互测试里的脚本替身管，这里只换掉抛掷入口。
        private final ScriptedInteractionSender scripted = new ScriptedInteractionSender();
        final List<Integer> requestedCounts = new ArrayList<>();
        Runnable onConfirmed = () -> {};

        @Override public PendingInteraction dropSelected(PlayerContext context, ItemStack expectedSelected,
                boolean fullStack, InteractionConfirmation confirmation, int timeoutTicks) {
            requestedCounts.add(fullStack ? expectedSelected.getCount() : 1);
            return scripted.useItem(context, InteractionHand.MAIN_HAND, confirmation, timeoutTicks);
        }

        @Override public PendingInteraction poll(PlayerContext context, PendingInteraction pending) {
            boolean willConfirm = scripted.nextStatus == PendingInteraction.Status.CONFIRMED_APPLIED
                    && pending == scripted.last && !pending.terminal();
            PendingInteraction settled = scripted.poll(context, pending);
            if (willConfirm && settled.terminal()) onConfirmed.run();
            return settled;
        }

        /** 设置下一次查询返回的终态。 */
        void next(PendingInteraction.Status status) { scripted.nextStatus = status; }
        PendingInteraction last() { return scripted.last; }

        @Override public PendingInteraction useBlock(PlayerContext c, InteractionHand h, BlockHitResult r,
                InteractionConfirmation cf, int t) { throw new UnsupportedOperationException(); }
        @Override public PendingInteraction useItem(PlayerContext c, InteractionHand h,
                InteractionConfirmation cf, int t) { throw new UnsupportedOperationException(); }
        @Override public PendingInteraction releaseUsingItem(PlayerContext c, PendingInteraction p) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction attack(PlayerContext c, Entity e,
                InteractionConfirmation cf, int t) { throw new UnsupportedOperationException(); }
        @Override public PendingInteraction interact(PlayerContext c, Entity e, InteractionHand h,
                InteractionConfirmation cf, int t) { throw new UnsupportedOperationException(); }
        @Override public PendingInteraction startBreaking(PlayerContext c, BlockHitResult r, int t) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction cancelBreaking(PlayerContext c, PendingInteraction p) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction cancelBreakingForTaskBoundary(
                PlayerContext c, PendingInteraction p, String reason) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction continueBreaking(PlayerContext c, PendingInteraction p) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction cancelMainHandUse(PlayerContext c, PendingInteraction p) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction selectHotbar(PlayerContext c, int slot, int t) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction creativeSetSlot(PlayerContext c, int slot, ItemStack e, int t) {
            throw new UnsupportedOperationException(); }
        @Override public PendingInteraction submitProtocol(PlayerContext c, String op, Runnable run,
                InteractionConfirmation cf, int t) { throw new UnsupportedOperationException(); }
        @Override public PendingInteraction submitControlProtocol(PlayerContext c, String op, Runnable run,
                InteractionConfirmation cf, int t) { throw new UnsupportedOperationException(); }
        @Override public PendingInteraction retireOneShotForTaskBoundary(
                PlayerContext c, PendingInteraction p, String reason) {
            throw new UnsupportedOperationException(); }
    }

    /** 角色上下文替身。 */
    static final class FakePlayerContext implements PlayerContext {
        long tick;
        private final InteractionSender sender;
        FakePlayerContext(InteractionSender sender) { this.sender = sender; }
        void advance() { tick++; }
        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() {
            return new PlayerInput() {
                @Override public boolean automationOwnsControls() { return true; }
                @Override public void applyMovement(Movement movement, long leaseTickRevision) {}
                @Override public void requestLook(float yaw, float pitch, long leaseTickRevision) {}
                @Override public void clearLook() {}
                @Override public void releaseAll() {}
                @Override public void lookAt(LocalPlayer player, Vec3 point) {}
                @Override public void halt(LocalPlayer player) {}
            };
        }
        @Override public InteractionSender interactionSender() { return sender; }
        @Override public MenuActions menuActions() { return null; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
    }

    /** 现场替身：主手握着给定数量的圆石；注册表先经引导才能造真实物品。 */
    static final class HandScene implements FirstPersonScene {
        int held;
        @Override public Vec3 eyePosition() { return new Vec3(0.0, 64.0, 0.0); }
        @Override public Vec3 viewVector() { return new Vec3(0.0, 0.0, -1.0); }
        @Override public net.minecraft.world.phys.HitResult sightRay() { return null; }
        @Override public net.minecraft.world.level.block.state.BlockState blockAt(net.minecraft.core.BlockPos pos) { return null; }
        @Override public boolean isLoaded(net.minecraft.core.BlockPos pos) { return false; }
        @Override public ItemStack heldItem(InteractionHand hand) {
            return held > 0 ? new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, held) : ItemStack.EMPTY;
        }
    }

    private static ReadsCharacterPosition atOrigin() {
        return () -> WorldPosition.here(0, 64, 0);
    }

    private static OffhandContents emptyOffhand() {
        return Optional::empty;
    }

    private static final class Rig {
        final FakeBackpack backpack;
        final FakeDropSender sender;
        final FakePlayerContext context;
        final HandScene scene;
        final DropAvoidance avoidance = new DropAvoidance();
        boolean autoConfirm = true;

        private Rig(FakeBackpack backpack, FakeDropSender sender, FakePlayerContext context, HandScene scene) {
            this.backpack = backpack;
            this.sender = sender;
            this.context = context;
            this.scene = scene;
        }

        static Rig create(int cobbleInBackpack, int heldInHand) {
            FakeBackpack backpack = new FakeBackpack();
            backpack.cobble = cobbleInBackpack;
            FakeDropSender sender = new FakeDropSender();
            HandScene scene = new HandScene();
            scene.held = heldInHand;
            return new Rig(backpack, sender, new FakePlayerContext(sender), scene);
        }

        DropTask task(int count) {
            return new DropTask(new DropInput(COBBLE, count), backpack, emptyOffhand(),
                    atOrigin(), ignored -> scene, Optional.empty(), Optional.empty(), avoidance);
        }

        TickResult run(DropTask task, int ticks) {
            TickResult result = TickResult.RUNNING;
            for (int i = 0; i < ticks; i++) {
                // 确认记录还没终结时，下一步按"游戏已确认"收尾；确认丢出时主手少掉相应件数。
                if (autoConfirm && sender.last() != null && !sender.last().terminal()) {
                    sender.next(PendingInteraction.Status.CONFIRMED_APPLIED);
                }
                context.advance();
                result = task.tick(asTickContext());
                if (result instanceof TickResult.Finished finished) return finished;
            }
            return result;
        }

        TickContext asTickContext() {
            long gameTick = context.tick;
            return new TickContext() {
                @Override public long gameTick() { return gameTick; }
                @Override public PlayerContext player() { return context; }
            };
        }
    }

    @Test
    void 丢五件_整份一抛_全部记进变化() {
        Rig rig = Rig.create(5, 5);
        rig.sender.onConfirmed = () -> {
            rig.scene.held = Math.max(0, rig.scene.held - 5);
            rig.backpack.cobble = 0;
        };
        TickResult result = rig.run(rig.task(5), 60);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE, "应该丢完五件");
        TaskResult done = ((TickResult.Finished) result).result();
        assertEquals(5, done.changes().stream()
                .filter(change -> change.kind() == Change.Kind.ITEM_DROPPED)
                .mapToInt(Change::count).sum());
        // 落点避让：默认视线朝北，落点登记在北面几格外。
        assertTrue(rig.avoidance.landings().stream()
                .anyMatch(pos -> pos.z() < 0 && Math.abs(pos.x()) <= 1));
    }

    @Test
    void 超量按实际持有丢_partial写清差额() {
        Rig rig = Rig.create(30, 30);
        rig.sender.onConfirmed = () -> {
            rig.scene.held = 0;
            rig.backpack.cobble = 0;
        };
        TickResult result = rig.run(rig.task(100), 60);
        TaskResult partial = result instanceof TickResult.Finished finished ? finished.result() : null;
        assertTrue(partial != null && partial.status() == TaskResult.Status.PARTIAL,
                () -> "结果：" + (partial == null ? "没结束" : partial.summary()));
        assertTrue(partial.remaining().stream().anyMatch(text -> text.contains("70")),
                () -> "remaining：" + partial.remaining());
        assertTrue(rig.sender.requestedCounts.contains(30), "整份一抛一次丢光手上这堆");
    }

    @Test
    void 没等到确认_记unconfirmed不重试() {
        Rig rig = Rig.create(30, 30);
        rig.autoConfirm = false;
        rig.sender.next(PendingInteraction.Status.UNCERTAIN);
        TickResult result = rig.run(rig.task(5), 60);
        TaskResult partial = result instanceof TickResult.Finished finished ? finished.result() : null;
        assertTrue(partial != null, () -> "结果没出来：" + result);
        assertEquals(1, partial.unconfirmed().size(), () -> "partial：" + partial.summary());
        assertTrue(partial.changes().isEmpty(), "没确认的不进 changes");
        // 没确认的抛掷不会再来一次。
        assertEquals(1, rig.sender.requestedCounts.size());
    }
}
