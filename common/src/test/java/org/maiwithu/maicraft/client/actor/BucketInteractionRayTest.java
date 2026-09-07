// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** Actual native task camera gate and actor USE_ITEM dispatch, with real vanilla block/fluid rays. */
public final class BucketInteractionRayTest {
    private static final BlockPos WATER = new BlockPos(3, 2, 3), MACHINE = new BlockPos(4, 2, 3);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        bucketUse(Items.BUCKET);
        bucketUse(Items.WATER_BUCKET);
        sourceDisappearsBeforePress();
        filledBucketMustPlaceInTargetCell();
        ordinaryBlockStillUsesBlock();
        requiredTypeGuardsSubmission();
        changedTargetAfterUseStillConfirms();
        System.out.println("BucketInteractionRayTest: passed");
    }

    private static void bucketUse(Item item) throws Exception {
        try (var f = world(item)) {
            var hit = FirstPersonInteractionTargeting.visibleBucketHit(f.level, f.player,
                    f.player.getEyePosition(), WATER, 4.5, item);
            check(hit != null && hit.getBlockPos().equals(item == Items.BUCKET ? WATER : MACHINE),
                    "empty/full bucket must reproduce native SOURCE_ONLY/NONE hit semantics");
            if (item == Items.WATER_BUCKET) check(hit.getBlockPos().relative(hit.getDirection()).equals(WATER),
                    "filled bucket must validate its actual native hit face");
            var task = task(f);
            check(act(task) == TaskState.RUNNING && f.mode.items == 0 && f.mode.blocks == 0,
                    "the first requested look cannot press a bucket before the camera gate");
            converge(f, task);
            check(act(task) == TaskState.RUNNING && f.mode.items == 1 && f.mode.blocks == 0,
                    "a bucket must submit one native USE_ITEM and never activate the backing machine");
        }
    }

    private static void sourceDisappearsBeforePress() throws Exception {
        try (var f = world(Items.BUCKET)) {
            var task = task(f); act(task); converge(f, task);
            f.set(WATER, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4));
            check(FirstPersonInteractionTargeting.visibleBucketHit(f.level, f.player,
                            f.player.getEyePosition(), WATER, 4.5, Items.BUCKET) == null,
                    "flowing water cannot pass the empty-bucket reach preflight");
            check(act(task) == TaskState.FAILED && f.mode.items == 0 && f.mode.blocks == 0,
                    "a source lost after aiming must fail before any native press");
        }
    }

    private static void filledBucketMustPlaceInTargetCell() throws Exception {
        try (var f = world(Items.WATER_BUCKET)) {
            f.set(MACHINE, Blocks.AIR.defaultBlockState());
            f.set(new BlockPos(6, 2, 3), Blocks.STONE.defaultBlockState());
            check(FirstPersonInteractionTargeting.visibleBucketHit(f.level, f.player,
                            f.player.getEyePosition(), WATER, 4.5, Items.WATER_BUCKET) == null,
                    "a distant backing solid cannot justify placement at a different cell");
            f.set(MACHINE, Blocks.OAK_SLAB.defaultBlockState());
            check(FirstPersonInteractionTargeting.visibleBucketHit(f.level, f.player,
                            f.player.getEyePosition(), WATER, 4.5, Items.WATER_BUCKET) == null,
                    "a waterloggable backing block cannot prove placement in the adjacent requested cell");
        }
    }

    private static void ordinaryBlockStillUsesBlock() throws Exception {
        try (var f = world(Items.STICK)) {
            f.set(WATER, Blocks.STONE.defaultBlockState());
            var task = task(f); act(task); converge(f, task); act(task);
            check(f.mode.blocks == 1 && f.mode.items == 0, "ordinary held items retain native block activation");
        }
    }

    private static void requiredTypeGuardsSubmission() throws Exception {
        try (var f = world(Items.BUCKET)) {
            var use = Interaction.useInAir(f.player, net.minecraft.world.InteractionHand.MAIN_HAND, Interaction.Timing.once())
                    .requireBlock(WATER, Blocks.WATER);
            var block = Interaction.useBlock(f.player, WATER, net.minecraft.world.InteractionHand.MAIN_HAND)
                    .requireBlock(WATER, Blocks.WATER);
            f.set(WATER, Blocks.STONE.defaultBlockState());
            check(use.tick() == Interaction.Status.FAILED && block.tick() == Interaction.Status.FAILED
                            && f.mode.items == 0 && f.mode.blocks == 0,
                    "native item/block submission must revalidate frozen identity even after primitive construction");
        }
    }

    private static void changedTargetAfterUseStillConfirms() throws Exception {
        try (var f = world(Items.BUCKET)) {
            var use = Interaction.useInAir(f.player, net.minecraft.world.InteractionHand.MAIN_HAND, Interaction.Timing.once())
                    .requireBlock(WATER, Blocks.WATER);
            check(use.tick() == Interaction.Status.RUNNING && f.mode.items == 1, "valid input permits native item use");
            f.set(WATER, Blocks.AIR.defaultBlockState());
            f.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            f.nextTick(); use.tick(); f.nextTick();
            check(use.tick() == Interaction.Status.DONE && f.mode.items == 1,
                    "an existing pickup receipt must confirm its changed target without reapplying the input assertion");
        }
    }

    private static InteractionWorldTestHarness world(Item item) throws Exception {
        var f = new InteractionWorldTestHarness();
        f.inventory.setItem(0, new ItemStack(item));
        f.set(WATER, Blocks.WATER.defaultBlockState());
        f.set(MACHINE, Blocks.STONE.defaultBlockState());
        return f;
    }

    private static InteractAtCompanionTask task(InteractionWorldTestHarness f) {
        return new InteractAtCompanionTask(f.player,
                new InteractAtTaskRecord("bucket", 100, MouseButton.RIGHT, WATER, 0, null));
    }

    private static void converge(InteractionWorldTestHarness f, InteractAtCompanionTask task) throws Exception {
        Vec3 aim = (Vec3) ActorControlTestHarness.field(InteractAtCompanionTask.class, "aimPoint").get(task);
        Vec3 direction = aim.subtract(f.player.getEyePosition());
        f.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        f.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.horizontalDistanceSqr()))));
        f.nextTick();
    }

    private static TaskState act(InteractAtCompanionTask task) throws Exception {
        Method method = InteractAtCompanionTask.class.getDeclaredMethod("act"); method.setAccessible(true);
        return (TaskState) method.invoke(task);
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
