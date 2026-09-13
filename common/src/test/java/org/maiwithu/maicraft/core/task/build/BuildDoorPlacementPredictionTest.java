// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.task.TaskState;

/** 原生预测的右合页不能被未声明的默认左合页推翻；保留两半、显式属性、真实扣物流程和服务器确认屏障。 */
public final class BuildDoorPlacementPredictionTest {
    private static final BlockPos AT = new BlockPos(5, 1, 5);
    private static final BlockState LEFT = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
    private static final BlockState RIGHT = LEFT.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
    private static final Set<String> FACING_HALF = Set.of("facing", "half");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        predictedPairAndAuthoredRequirements();
        nativePredictionAndAcknowledgementStillOwnCompletion();
        unexpectedMaterialDeltaIsNotAFreePlacement();
        resumedCorrectDoorIsReused();
        System.out.println("BuildDoorPlacementPredictionTest: passed");
    }

    private static void predictedPairAndAuthoredRequirements() {
        var lower = target(LEFT, AT, FACING_HALF); var upper = target(upper(LEFT), AT.above(), FACING_HALF);
        var confirmation = confirmation(lower, upper, RIGHT); var states = pair(RIGHT);
        check(confirmation.observe(p -> true, states::get, true) == Verdict.APPLIED,
                "both native right-hinged halves satisfy authored facing/half without inheriting an undeclared left hinge");
        var effects = (List<?>) confirmation.diagnostics(p -> true, states::get).get("effects");
        check(((Map<?, ?>) effects.get(1)).get("expected").toString().contains("hinge=right")
                && ((Map<?, ?>) effects.get(1)).get("authored").toString().contains("hinge=left"), "diagnostics distinguish the native expectation from the authored default");
        states.put(AT.above(), Blocks.AIR.defaultBlockState());
        check(confirmation.observe(p -> true, states::get, true) == Verdict.PENDING, "a delayed upper half still waits after the server acknowledged the use");
        check(confirmation.observe(p -> !p.equals(AT.above()), states::get, true) == Verdict.PENDING, "an unloaded generated cell remains unknown");
        states.put(AT.above(), upper(RIGHT)); states.put(AT, RIGHT.setValue(DoorBlock.FACING, Direction.SOUTH));
        check(confirmation.observe(p -> true, states::get, true) == Verdict.DIVERGED, "native prediction cannot hide a wrong lower facing");
        states.put(AT, RIGHT); states.put(AT.above(), upper(LEFT));
        check(confirmation.observe(p -> true, states::get, true) == Verdict.DIVERGED, "opposite hinges on the two halves are not a completed door");
        states.put(AT.above(), Blocks.STONE.defaultBlockState());
        check(confirmation.observe(p -> true, states::get, true) == Verdict.DIVERGED, "an unrelated upper block stays a real divergence");
        var explicit = Set.of("facing", "half", "hinge");
        check(confirmation(target(LEFT, AT, explicit), upper, RIGHT).observe(p -> true, pair(RIGHT)::get, true) == Verdict.DIVERGED,
                "an explicitly required lower hinge cannot be replaced by the predicted one");
        check(confirmation(lower, target(upper(LEFT), AT.above(), explicit), RIGHT).observe(p -> true, pair(RIGHT)::get, true) == Verdict.DIVERGED,
                "an explicitly required upper hinge is independently enforced");
        check(confirmation.observe(p -> true, p -> Blocks.AIR.defaultBlockState(), true) == Verdict.NOT_APPLIED,
                "an acknowledged unchanged site does not claim placement or consume a placement budget");
        check(lower.materialCount() == 1 && upper.materialCount() == 0 && confirmation.requiresBlockAcknowledgement(),
                "one real door item funds the complete pair, and the server acknowledgement contract remains mandatory");
    }

    private static void nativePredictionAndAcknowledgementStillOwnCompletion() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 1, 8.5)); h.inventory.setItem(0, new ItemStack(Items.DARK_OAK_DOOR, 1)); h.inventory.selected = 0;
            var lower = target(LEFT, AT, FACING_HALF); BlockState predicted = null; BlockHitResult hit = null;
            // 用原生放置预测找到真实的右合页点击，不能用作者默认状态冒充游戏这次会放出的门。
            for (double x : new double[]{.2, .8}) for (double z : new double[]{.2, .8}) {
                var candidate = new BlockHitResult(new Vec3(AT.getX() + x, AT.getY(), AT.getZ() + z), Direction.UP, AT.below(), false);
                var result = BuildPlacementGeometry.predict(h.player, lower, candidate, 180, 0);
                if (result != null && result.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT) { predicted = result; hit = candidate; }
            }
            check(predicted != null && predicted.getValue(DoorBlock.FACING) == Direction.NORTH, "the native fixture must actually predict a right-hinged north-facing door");
            var upper = target(upper(LEFT), AT.above(), FACING_HALF); var confirmation = confirmation(lower, upper, predicted).trackMaterial(h.player);
            var expected = predicted; Object mode = field(h.getClass(), "mode").get(h);
            // 这里只模拟服务器先同步两半、稍后同步一次扣物；实际提交/确认编号/重复点击防护仍走真实动作端口。
            field(mode.getClass(), "beforeBlockUse").set(mode, (Runnable) () -> {
                h.set(AT, expected); h.set(AT.above(), upper(expected));
            });
            var context = ClientRuntime.requireContext(h.player);
            var receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, confirmation, 40);
            check(h.inventory.getItem(0).getCount() == 1 && h.blockUses() == 1 && !receipt.terminal(), "visible pair cannot finish before the native server acknowledgement");
            for (int i = 0; i < 3; i++) { h.nextTick(); context = ClientRuntime.requireContext(h.player); context.actions().poll(context, receipt); }
            check(!receipt.terminal(), "client prediction remains pending without server acknowledgement");
            h.level.acknowledgedSequence = h.level.blockSequence; h.nextTick(); context = ClientRuntime.requireContext(h.player);
            context.actions().poll(context, receipt);
            check(!receipt.terminal(), "acknowledged pair still waits for the actual one-item material deduction");
            h.inventory.getItem(0).shrink(1); h.nextTick(); context = ClientRuntime.requireContext(h.player); context.actions().poll(context, receipt);
            check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED && h.inventory.isEmpty() && h.blockUses() == 1 && h.itemUses() == 0,
                    "the acknowledged native pair settles exactly once without a second placement or demolition");
        }
    }

    private static void resumedCorrectDoorIsReused() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT, RIGHT); h.set(AT.above(), upper(RIGHT));
            var lower = target(LEFT, AT, FACING_HALF); var upper = target(upper(LEFT), AT.above(), FACING_HALF);
            var record = new BuildTaskRecord("existing-right-hinge", 1000, List.of(lower, upper), false, true); record.previewManaged(true);
            var task = new FirstPersonBuildCompanionTask(h.player, record); task.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 12 && state == TaskState.RUNNING; i++) { h.nextTick(); state = task.tick(h.player); }
            check(state == TaskState.SUCCESS && record.completed() == 2 && record.placed() == 0 && record.broken() == 0
                    && h.blockUses() == 0 && h.itemUses() == 0 && h.inventory.isEmpty(),
                    "resuming re-reads the already-correct pair without fetching another item or breaking/replacing the door");
            task.result(state);
        }
    }
    private static void unexpectedMaterialDeltaIsNotAFreePlacement() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var lower = target(LEFT, AT, FACING_HALF); var upper = target(upper(LEFT), AT.above(), FACING_HALF);
            h.inventory.setItem(0, new ItemStack(Items.DARK_OAK_DOOR, 2));
            var confirmation = confirmation(lower, upper, RIGHT).trackMaterial(h.player);
            h.set(AT, RIGHT); h.set(AT.above(), upper(RIGHT)); h.inventory.getItem(0).shrink(2);
            check(confirmation.observeAcknowledged(ClientRuntime.requireContext(h.player)) == Verdict.DIVERGED,
                    "one generated door pair cannot quietly charge two door items");
            h.set(AT, Blocks.AIR.defaultBlockState()); h.set(AT.above(), Blocks.AIR.defaultBlockState());
            h.inventory.setItem(0, new ItemStack(Items.DARK_OAK_DOOR, 1));
            var unchanged = confirmation(lower, upper, RIGHT).trackMaterial(h.player); h.inventory.getItem(0).shrink(1);
            check(unchanged.observeAcknowledged(ClientRuntime.requireContext(h.player)) == Verdict.DIVERGED,
                    "an item lost without the door cannot be treated as a harmless refused click and retried");
            h.player.getAbilities().instabuild = true;
            var creative = confirmation(lower, upper, RIGHT).trackMaterial(h.player); h.set(AT, RIGHT); h.set(AT.above(), upper(RIGHT));
            check(creative.observeAcknowledged(ClientRuntime.requireContext(h.player)) == Verdict.APPLIED,
                    "creative mode keeps its native non-consuming semantics rather than inventing a survival deduction");
        }
    }
    private static BuildPlacementConfirmation confirmation(BuildTaskRecord.Target lower, BuildTaskRecord.Target upper, BlockState predicted) {
        return new BuildPlacementConfirmation(lower, BuildPlacementGeometry.generatedBy(lower),
                Map.of(AT.asLong(), Blocks.AIR.defaultBlockState(), AT.above().asLong(), Blocks.AIR.defaultBlockState()), predicted,
                Map.of(AT.asLong(), lower, AT.above().asLong(), upper));
    }
    private static BuildTaskRecord.Target target(BlockState state, BlockPos at, Set<String> properties) {
        return new BuildTaskRecord.Target(state, Items.DARK_OAK_DOOR, at, "door", null, null, null, false, properties, true, properties);
    }
    private static BlockState upper(BlockState state) { return state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER); }
    private static Map<BlockPos, BlockState> pair(BlockState state) { return new HashMap<>(Map.of(AT, state, AT.above(), upper(state))); }
    private static Field field(Class<?> owner, String name) throws Exception { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
