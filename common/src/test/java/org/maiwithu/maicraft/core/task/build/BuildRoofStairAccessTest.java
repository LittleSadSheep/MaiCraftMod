// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;

/** 东檐原型整体下移75格：已有土脚手架／相邻楼梯只作真实地形，搜路和原生朝向预测不放任何方块。 */
public final class BuildRoofStairAccessTest {
    private static final BlockPos PREVIOUS = new BlockPos(11, 1, 5), TARGET = new BlockPos(11, 1, 6);
    private static final Vec3 OUTSIDE = new Vec3(12.5, 1, 5.5), TOP = new Vec3(11.5, 2, 5.5), EDGE = new Vec3(11.5, 2, 6.15);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        actualOutsideStart(); unfinishedInnerFloorRequiresAscending(); removingTheRealStepInvalidatesItsProof();
        System.out.println("BuildRoofStairAccessTest: real outside start, raised stair edge and native west/bottom placement passed");
    }

    private static void actualOutsideStart() throws Exception {
        try (var h = fixture(true)) {
            h.position(OUTSIDE);
            check(h.level.getBlockState(new BlockPos(10, 1, 5)).is(Blocks.SPRUCE_PLANKS),
                    "the occupied inner roof cannot be presented as an available same-height standing cell");
            var target = target(); var view = view(h); var search = search(h, target, view); finish(search);
            check(search.accepted(), "the actual existing outside support must yield a bounded native access: " + search.reason());
            var access = search.access();
            // 当前脚下是已有整块土支撑，能直接按正确朝向放楼梯时应复用原位；旧测试把所有屋顶放置都强制当成临边。
            check(access.feet().equals(OUTSIDE) && access.approach().equals(OUTSIDE) && !access.edge(),
                    "the complete existing outside dirt support permits placement without unnecessary edge motion");
            var footprint = h.player.getBoundingBox();
            var support = h.level.getBlockState(new BlockPos(12, 0, 5)).getCollisionShape(h.level, new BlockPos(12, 0, 5)).bounds().move(12, 0, 5);
            check(support.minX <= footprint.minX && support.maxX >= footprint.maxX && support.minZ <= footprint.minZ
                            && support.maxZ >= footprint.maxZ && Math.abs(support.maxY - footprint.minY) < 1e-6,
                    "the retained position has its complete real footprint on the existing support surface");
            check(access.route().stream().allMatch(feet -> feet.y >= OUTSIDE.y) && access.feet().y >= OUTSIDE.y,
                    "neither the retained route nor its final stance may descend off the roof");
            check(access.gesture().sneak() == BuildPlacementInteraction.requiresSneak(h.level.getBlockState(access.gesture().clicked())),
                    "complete roof support does not add crouching beyond the clicked block's interaction requirement");
            verifyRoute(h, access); verifyNative(h, target, access.feet(), access.gesture());
            // 普通站位优先不代表丢掉真正的上方临边能力；仍独立验证升高后部分支撑的潜行放法。
            verifyRaisedAlternative(h, target);
            unchanged(h, view);
            System.out.println("actual roof stair access: anchor=" + access.approach() + ", feet=" + access.feet());
        }
    }

    private static void unfinishedInnerFloorRequiresAscending() throws Exception {
        try (var h = fixture(false)) {
            // 这是内侧木地板尚未施工的独立合法场景，不冒充当前实机已经被木地板占满的位置。
            h.position(new Vec3(10.5, 1, 5.5));
            check(h.level.getBlockState(new BlockPos(10, 1, 5)).isAir(), "the alternative start really is unobstructed");
            var target = target(); var view = view(h);
            check(BuildPlacementGeometry.currentGesture(h.player, target, Map.of()) == null,
                    "from the inner same-height floor, looking outward cannot produce the authored west-facing stair");
            var search = search(h, target, view); finish(search);
            check(search.accepted(), "an existing adjacent stair should provide raised access: " + search.reason());
            var access = search.access();
            check(access.approach().y > h.player.getY() + .5 && access.feet().y == access.approach().y,
                    "the accepted search actually climbs the existing stair before the same-height final edge segment");
            verifyRoute(h, access); verifyNative(h, target, access.feet(), access.gesture()); unchanged(h, view);
        }
    }

    private static void removingTheRealStepInvalidatesItsProof() throws Exception {
        try (var h = fixture(true)) {
            h.position(OUTSIDE); var target = target(); verifyRaisedAlternative(h, target);
            h.set(PREVIOUS, Blocks.AIR.defaultBlockState());
            check(!ground(h).clear(TOP, EDGE), "a remembered stair top is not support after the stair is removed");
            check(h.level.getBlockState(TARGET).isAir() && h.blockUses() == 0, "the rejected alternative never installs a substitute support");
        }
    }

    private static void verifyRaisedAlternative(InteractionWorldTestHarness h, BuildTaskRecord.Target target) {
        var walking = walking(h);
        check(TOP.equals(walking.stance(PREVIOUS.above())) && walking.edge(OUTSIDE, TOP),
                "the actual stair collision shape supports a one-block rise from the outside dirt");
        check(ground(h).clear(TOP, EDGE), "the entire 0.65-block upper edge retains real stair support");
        check(!BuildFootprintSupport.complete(h.level, h.level::isLoaded, h.player.getBbWidth(), TOP, EDGE, EDGE),
                "the upper outward segment is a genuine partially supported edge, unlike the full dirt platform");
        var gesture = BuildPlacementGeometry.projectedGestureFrom(h.player, target, view(h), h.level::isLoaded, EDGE, true);
        check(gesture != null && gesture.sneak(), "the genuine upper edge must retain crouching and a real visible native placement face");
        verifyNative(h, target, EDGE, gesture);
    }

    private static void verifyRoute(InteractionWorldTestHarness h, BuildPlacementAccessSearch.Access access) {
        check(!access.route().isEmpty() && access.route().getFirst().equals(h.player.position()), "the proof begins at the actual current feet");
        var walking = walking(h);
        for (int i = 1; i < access.route().size(); i++) {
            Vec3 from = access.route().get(i - 1), to = access.route().get(i);
            check(Math.abs(from.y - to.y) < 1e-5 ? ground(h).clear(from, to) : walking.edge(from, to),
                    "each retained prefix is an existing supported corridor or native-sized step");
        }
        check(access.approach().distanceTo(access.feet()) <= .7 && ground(h).clear(access.approach(), access.feet()),
                "the final continuous target is proved separately from ordinary navigation");
    }

    private static void verifyNative(InteractionWorldTestHarness h, BuildTaskRecord.Target target, Vec3 feet,
                                     BuildPlacementGeometry.Gesture gesture) {
        // 原生射线必须使用这份实际候选姿态的眼高，不能用潜行眼高去校验已经证明合法的站立放法。
        Vec3 eye = feet.add(0, h.player.getEyeHeight(gesture.sneak() ? Pose.CROUCHING : Pose.STANDING), 0);
        Vec3 end = gesture.point().add(gesture.point().subtract(eye).normalize().scale(.01));
        var hit = h.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, h.player));
        check(hit.getBlockPos().equals(gesture.clicked()) && hit.getDirection() == gesture.face(),
                "the native whole-world ray must hit the declared support before any other block");
        var context = new BlockPlaceContext(new UseOnContext(h.level, h.player, InteractionHand.MAIN_HAND,
                new ItemStack(Items.DARK_OAK_STAIRS), hit) {}) {
            @Override public Direction getHorizontalDirection() { return Direction.fromYRot(gesture.yaw()); }
            @Override public float getRotation() { return gesture.yaw(); }
            @Override public boolean isSecondaryUseActive() { return gesture.sneak(); }
        };
        BlockState nativeState = Blocks.DARK_OAK_STAIRS.getStateForPlacement(context);
        check(context.getClickedPos().equals(TARGET) && nativeState != null && target.acceptsPlacedState(nativeState)
                        && nativeState.getValue(StairBlock.FACING) == Direction.WEST && nativeState.getValue(StairBlock.HALF) == Half.BOTTOM,
                "the installed native stair placement must produce the exact authored destination, facing and half");
    }

    private static InteractionWorldTestHarness fixture(boolean outside) throws Exception {
        var h = new InteractionWorldTestHarness();
        var dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6f, 1.8f)); h.player.setDeltaMovement(0, -.0784, 0);
        // 删除夹具默认大地，只留下与檐边有关的实际石英带，不能让隐藏的平地帮搜索绕到屋外。
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.set(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
        for (int x = 10; x <= 11; x++) for (int z = 4; z <= 8; z++) h.set(new BlockPos(x, 0, z), Blocks.SMOOTH_QUARTZ.defaultBlockState());
        h.set(PREVIOUS, target().desiredState());
        if (outside) {
            h.set(new BlockPos(12, 0, 5), Blocks.DIRT.defaultBlockState());
            for (int z = 4; z <= 8; z++) h.set(new BlockPos(10, 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        }
        h.inventory.setItem(0, new ItemStack(Items.DARK_OAK_STAIRS, 8)); return h;
    }
    private static BuildTaskRecord.Target target() {
        return new BuildTaskRecord.Target(Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.BOTTOM), Items.DARK_OAK_STAIRS, TARGET, "east eave", null, null, null,
                false, Set.of("facing", "half"), true, Set.of("facing", "half"));
    }
    private static BuildSupportWorld view(InteractionWorldTestHarness h) { return new BuildSupportWorld(h.level, h.level::isLoaded, Map.of()); }
    private static GroundCorridor ground(InteractionWorldTestHarness h) { return new GroundCorridor(h.level, h.level::isLoaded, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY); }
    private static BuildSupportWalking walking(InteractionWorldTestHarness h) { return new BuildSupportWalking(h.level, h.level::isLoaded, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY); }
    private static BuildPlacementAccessSearch search(InteractionWorldTestHarness h, BuildTaskRecord.Target target, BuildSupportWorld view) {
        return new BuildPlacementAccessSearch(h.player, target, view, h.player.position(), LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY, 512, true);
    }
    private static void finish(BuildPlacementAccessSearch search) {
        for (int tick = 0; tick < 4096; tick++) if (search.advance(16)) return;
        throw new AssertionError("bounded roof stair search never completed");
    }
    private static void unchanged(InteractionWorldTestHarness h, BuildSupportWorld view) {
        check(view.unchanged() && h.level.getBlockState(TARGET).isAir() && h.inventory.getItem(0).getCount() == 8
                && h.blockUses() == 0 && h.itemUses() == 0, "a successful access proof changes neither world nor materials and submits no click");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
