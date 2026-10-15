// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 跨维度出行的离线测试替身：摆现场、演找门与用品，不碰游戏对象。 */
final class DimensionFakes {

    private DimensionFakes() {}

    /** 现场替身：维度、脚下、找门脚本、门活没活、身体沾没沾门格、方块表、身上有什么。 */
    static final class FakeWorld implements DimensionWorldView {
        String dimension = DimensionRoute.OVERWORLD;
        BlockPos feet = new BlockPos(0, 64, 0);
        String carried;
        /** 身上的物品栈：数黑曜石、挑石料用；carries 也认这里面的。 */
        final List<BackpackStack> stacks = new ArrayList<>();
        boolean touching;
        /** stepAsideCell 的答案；没给就给门口旁一格。 */
        BlockPos aside = new BlockPos(3, 64, 0);
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<Block> alivePortals = new HashSet<>();
        final Map<Block, Boolean> alive = new HashMap<>();
        final Deque<PortalFinder.Round> portalRounds = new ArrayDeque<>();
        final Deque<Scan> blockScans = new ArrayDeque<>();
        /** 身体沾没沾门格的脚本：每步可以先跑一段现场改动（比如把维度换过去），再回答这一刻沾没沾。 */
        final Deque<Map.Entry<Boolean, Runnable>> touchScript = new ArrayDeque<>();

        void putPortal(Block block, BlockPos cell) {
            alivePortals.add(block);
            blocks.put(cell, portalState(block));
        }

        static BlockState portalState(Block block) {
            return (block == Blocks.NETHER_PORTAL ? Blocks.NETHER_PORTAL : Blocks.END_PORTAL)
                    .defaultBlockState();
        }

        void enqueuePortals(PortalFinder.Round... rounds) {
            for (PortalFinder.Round round : rounds) {
                portalRounds.add(round);
            }
        }

        void enqueueScan(Scan... scans) {
            for (Scan scan : scans) {
                blockScans.add(scan);
            }
        }

        @Override public String currentDimension() {
            return dimension;
        }

        @Override public BlockPos feet() {
            return feet;
        }

        @Override public PortalFinder.Round scanPortals(Block portalBlock, int searchDistance, Set<Long> attempted) {
            return portalRounds.isEmpty() ? new PortalFinder.Round(List.of(), true) : portalRounds.remove();
        }

        @Override public boolean portalAlive(Block portalBlock, BlockPos cell) {
            if (alive.containsKey(portalBlock)) {
                return alive.get(portalBlock);
            }
            BlockState state = blocks.get(cell);
            return state != null && state.is(portalBlock);
        }

        @Override public boolean touchesPortal(Block portalBlock, Set<Long> portalCells) {
            if (!touchScript.isEmpty()) {
                Map.Entry<Boolean, Runnable> step = touchScript.poll();
                if (step.getValue() != null) {
                    step.getValue().run();
                }
                return step.getKey();
            }
            return touching;
        }

        @Override public Optional<BlockPos> stepAsideCell(Set<Long> portalCells) {
            return Optional.ofNullable(aside);
        }

        @Override public Optional<BlockState> stateAt(BlockPos cell) {
            return Optional.ofNullable(blocks.get(cell));
        }

        @Override public boolean carries(String itemId) {
            return itemId.equals(carried) || stacks.stream().anyMatch(stack -> itemId.equals(stack.itemId()));
        }

        @Override public List<BackpackStack> carriedStacks() {
            return List.copyOf(stacks);
        }

        @Override public Scan scanBlocks(Block target, int searchDistance, int want) {
            return blockScans.isEmpty() ? new Scan(List.of(), true) : blockScans.remove();
        }
    }

    /** 角色上下文替身：只供输入入口；直走进门的判断都走现场替身。 */
    static final class FakeContext implements PlayerContext {
        final FakeInput input = new FakeInput();

        @Override public LocalPlayer localPlayer() {
            return null;
        }

        @Override public ClientLevel level() {
            return null;
        }

        @Override public ClientPacketListener connection() {
            throw new UnsupportedOperationException();
        }

        @Override public PlayerInput input() {
            return input;
        }

        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException();
        }

        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException();
        }

        @Override public long clientTick() {
            return 0;
        }

        @Override public boolean isCurrent() {
            return true;
        }

        @Override public boolean canInteractThisTick() {
            return false;
        }

        @Override public boolean tryClaimInteraction() {
            return false;
        }
    }

    /** 输入替身：记下看向哪里、按没按前进、停没停。 */
    static final class FakeInput implements PlayerInput {
        int halts;
        int forwardTicks;
        Vec3Like lookedAt;

        record Vec3Like(double x, double y, double z) {}

        @Override public void applyMovement(Movement movement, long requestTick) {
            if (movement.forward() > 0) {
                forwardTicks++;
            }
        }

        @Override public void requestLook(float yaw, float pitch, long requestTick) {}

        @Override public void clearLook() {}

        @Override public void releaseAll() {}

        @Override public void lookAt(LocalPlayer player, Vec3 point) {
            lookedAt = new Vec3Like(point.x, point.y, point.z);
        }

        @Override public void halt(LocalPlayer player) {
            halts++;
        }

        @Override public boolean automationOwnsControls() {
            return true;
        }
    }

    /** 走到替身：复用出行的脚本走到，另记下收到的导航目标。 */
    static final class FakeWalks implements WalkTo {
        final List<GoalCompiler.Compiled> started = new ArrayList<>();
        final Deque<TravelFakes.ScriptedWalk> walks = new ArrayDeque<>();
        private TravelFakes.ScriptedWalk current;

        void enqueue(TravelFakes.ScriptedWalk walk) {
            walks.add(walk);
        }

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            started.add(target);
            // 脚本用完了就用一段"两刻走到"顶上：走近、离门、最后一段步行都是走得成的。
            current = walks.isEmpty() ? arrived() : walks.remove();
            return current;
        }

        private TravelFakes.ScriptedWalk arrived() {
            return new TravelFakes.ScriptedWalk(
                    WalkReport.onTheWay(new BlockPos(1, 64, 1), false),
                    WalkReport.arrived(new BlockPos(1, 64, 1), false));
        }
    }

    /** 拿东西替身：按脚本给动作或失败。 */
    static final class FakeObtain implements ItemNeeds {
        final List<ItemRequest> asked = new ArrayList<>();
        /** 依次回答每次请求：null 表示成功（一刻完成），否则按这个问题失败。 */
        final Deque<Problem> answers = new ArrayDeque<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request);
            Problem problem = answers.isEmpty() ? null : answers.remove();
            return problem == null ? immediate(() -> { }) : failing(problem);
        }

        static Action immediate(Runnable effect) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    effect.run();
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身动作";
                }
            };
        }

        static Action failing(Problem problem) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.failed(problem);
                }

                @Override public String describe() {
                    return "替身失败";
                }
            };
        }
    }

    /** 换手替身：换到主手给一刻完成的动作。 */
    static final class FakeToMainhand implements MovesToMainhand {
        final List<String> moved = new ArrayList<>();

        @Override public Optional<Action> moveToMainhand(String itemId) {
            moved.add(itemId);
            return Optional.of(FakeObtain.immediate(() -> { }));
        }
    }

    /** 用一格方块替身：给一刻完成的动作，记下点过哪些格。 */
    static final class FakeUses implements UsesBlocks {
        final List<BlockPos> clicked = new ArrayList<>();
        /** 点完后要不要让世界变成"门活了"：由测试在点击效果里改现场。 */
        final Deque<Runnable> effects = new ArrayDeque<>();

        @Override public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
            clicked.add(target);
            Runnable effect = effects.isEmpty() ? () -> { } : effects.remove();
            return FakeObtain.immediate(effect);
        }
    }

    /** 演一段"到门口"的走到。 */
    static TravelFakes.ScriptedWalk arrivedWalk() {
        return new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 64, 1), false),
                WalkReport.arrived(new BlockPos(1, 64, 1), false));
    }

    /** 演一段走不通的走到。 */
    static TravelFakes.ScriptedWalk failedWalk(String why) {
        return new TravelFakes.ScriptedWalk(
                WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, why, null), new BlockPos(0, 64, 0), false));
    }
}
