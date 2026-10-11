// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 随行插火把的临时任务：选眼前的灯位、换火把、插下去；没做成也如实收场并记下出过手。 */
class PlaceTorchTaskTest {

    private static final BlockPos LAMP = new BlockPos(0, 65, -1);
    private static final BlockPos WALL = new BlockPos(0, 65, -2);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 正前方一面石头墙的第一人称现场，主手可换成空。 */
    private static final class FakeScene implements FirstPersonScene {
        ItemStack mainhand = new ItemStack(Items.TORCH);

        @Override public Vec3 eyePosition() { return new Vec3(0.5, 65.62, 0.5); }
        @Override public Vec3 viewVector() { return new Vec3(0, 0, -1); }
        @Override public HitResult sightRay() { return new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, true); }
        @Override public BlockHitResult visibleHit(BlockPos target) {
            return target.equals(WALL) ? new BlockHitResult(
                    new Vec3(0.5, 65.5, -2), Direction.SOUTH, WALL, false) : null;
        }
        @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) { return null; }
        @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) { return false; }
        @Override public BlockState blockAt(BlockPos pos) {
            if (pos.equals(WALL)) return Blocks.STONE.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        @Override public boolean isLoaded(BlockPos pos) { return true; }
        @Override public ItemStack heldItem(InteractionHand hand) {
            return hand == InteractionHand.MAIN_HAND ? mainhand : ItemStack.EMPTY;
        }
    }

    /** 一刻做完的动作替身。 */
    private static final class InstantAction implements Action {
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
        @Override public void pause() { }
        @Override public void close() { }
        @Override public String describe() { return "替身动作"; }
    }

    /** 出手脚本：动作一刻做完，结论从队列里依次取。 */
    private static PlaceTorchTask.Clicks clicks(Deque<InteractionResult> results) {
        return spot -> new PlaceTorchTask.Click(new InstantAction(), results::poll);
    }

    private static TaskResult run(PlaceTorchTask task) {
        TickContext context = TickContext.of(1, null);
        task.start(context);
        for (int i = 0; i < 200; i++) {
            TickResult result = task.tick(context);
            if (result instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("两百刻还没收场");
    }

    private static PlaceTorchTask task(FakeScene scene, List<BlockPos> attempts,
                                       Deque<InteractionResult> results, Optional<Action> move) {
        return new PlaceTorchTask(context -> scene, itemId -> move,
                pos -> false, pos -> false, attempts::add, clicks(results));
    }

    @Test
    void 插上了_记变化并算出过手() {
        FakeScene scene = new FakeScene();
        List<BlockPos> attempts = new ArrayList<>();
        Deque<InteractionResult> results = new ArrayDeque<>(List.of(InteractionResult.applied("替身")));
        TaskResult result = run(task(scene, attempts, results, Optional.empty()));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, result.changes().size());
        assertEquals(LAMP, attempts.getFirst(), "成不成都算出手过");
    }

    @Test
    void 手上没火把_换到主手再插() {
        FakeScene scene = new FakeScene();
        scene.mainhand = ItemStack.EMPTY;
        Deque<InteractionResult> results = new ArrayDeque<>(List.of(InteractionResult.applied("替身")));
        TaskResult result = run(task(scene, new ArrayList<>(), results, Optional.of(new InstantAction())));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().contains("火把"));
    }

    @Test
    void 包里没火把_如实收场不牵连主任务() {
        FakeScene scene = new FakeScene();
        scene.mainhand = ItemStack.EMPTY;
        TaskResult result = run(task(scene, new ArrayList<>(), new ArrayDeque<>(), Optional.empty()));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
    }

    @Test
    void 游戏没接受_记出过手_如实收场() {
        FakeScene scene = new FakeScene();
        List<BlockPos> attempts = new ArrayList<>();
        Deque<InteractionResult> results = new ArrayDeque<>(
                List.of(InteractionResult.notApplied("那一格被占住")));
        TaskResult result = run(task(scene, attempts, results, Optional.empty()));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
        assertEquals(1, attempts.size(), "被拒也算出手过，两格节制照记");
    }

    @Test
    void 没能确认结果_记进未确认_不冒充插上() {
        FakeScene scene = new FakeScene();
        Deque<InteractionResult> results = new ArrayDeque<>(
                List.of(InteractionResult.unconfirmed("服务端没回")));
        TaskResult result = run(task(scene, new ArrayList<>(), results, Optional.empty()));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(1, result.unconfirmed().size(), "提交了但没能确认的交互不能盲目重做");
    }

    @Test
    void 眼前找不到能插的地方_如实收场() {
        FakeScene scene = new FakeScene();
        List<BlockPos> attempts = new ArrayList<>();
        // 保护全开：一个灯座都挑不出来。
        PlaceTorchTask guarded = new PlaceTorchTask(context -> scene, itemId -> Optional.empty(),
                pos -> false, pos -> true, attempts::add,
                spot -> new PlaceTorchTask.Click(new InstantAction(), () -> null));
        TaskResult result = run(guarded);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(attempts.isEmpty(), "没出手就不算出过手");
        assertTrue(result.problem().message().contains("找不到"));
    }
}
