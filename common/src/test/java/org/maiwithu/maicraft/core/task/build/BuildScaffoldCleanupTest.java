// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import sun.misc.Unsafe;

/** Native Minecraft clip/collision regression; candidate geometry does not claim that navigation already walked it. */
public final class BuildScaffoldCleanupTest {
    private static final BlockPos TARGET = new BlockPos(8, 1, 8);
    private BuildScaffoldCleanupTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        nearbyOccludedPositionNeedsAnotherStance();
        removalMustRetainIndependentFooting();
        forbiddenAirborneAndUnloadedPositionsAreRejected();
        bodyCollisionAndHazardDoNotBecomeSafeShots();
        partialOutlineMustUseTheSameNativeFaceProbes();
        System.out.println("BuildScaffoldCleanupTest: 5 native geometry groups passed; no navigation or digging actions submitted");
    }

    private static void nearbyOccludedPositionNeedsAnotherStance() throws Exception {
        try (var world = scene(new Vec3(8.5, 1, 6.5))) {
            for (int x = 6; x <= 10; x++) for (int y = 1; y <= 3; y++) world.set(new BlockPos(x, y, 7), Blocks.STONE.defaultBlockState());
            check(world.player.getEyePosition().distanceToSqr(TARGET.getCenter()) <= 20.25, "fixture reproduces old distance-only arrival");
            check(nativeHit(world, TARGET) == null, "all seven native target probes are blocked from the original position");
            var cleanup = new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet());
            check(!cleanup.ready(), "being close to a hidden support does not authorize breaking");
            BuildScaffoldCleanup.Candidate candidate = candidate(cleanup);
            check(candidate != null, "a bounded search should find a visible stance beside the wall");
            world.position(candidate.feet());
            check(cleanup.ready() && nativeHit(world, TARGET) != null, "after actual repositioning the existing BlockDigger can hit the exact target");
            cleanup.rejectCurrent();
            check(!cleanup.ready(), "a rejected actual stance cannot immediately be accepted again");
            BuildScaffoldCleanup.Candidate alternative = candidate(cleanup);
            check(alternative != null && alternative.feet().distanceToSqr(candidate.feet()) > 1e-8,
                    "next candidate must not repeat a rejected physical stance through another cell alias");
            unchanged(world);
        }
    }

    private static void removalMustRetainIndependentFooting() throws Exception {
        try (var world = scene(new Vec3(8.5, 2, 8.5))) {
            check(nativeHit(world, TARGET) != null, "standing on the target can still produce a valid target ray");
            var cleanup = new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet());
            check(!cleanup.ready(), "an owned support must not be removed from under the player's feet");
            BuildScaffoldCleanup.Candidate candidate = candidate(cleanup);
            check(candidate != null, "a grounded side stance remains available");
            check(!(Math.abs(candidate.feet().y - 2) < 1e-6
                    && horizontalContact(candidate.feet(), world.player.getBbWidth(), new AABB(TARGET))),
                    "the candidate cannot use the target as its sole elevated footing");
            world.position(candidate.feet());
            check(cleanup.ready() && nativeHit(world, TARGET) != null, "replacement stance is independently supported and visible");
            unchanged(world);
        }
    }

    private static void forbiddenAirborneAndUnloadedPositionsAreRejected() throws Exception {
        try (var world = scene(new Vec3(8.5, 1, 5.5))) {
            check(nativeHit(world, TARGET) != null, "the protection fixture starts with a clear target ray");
            var forbidden = new LongOpenHashSet(); forbidden.add(new BlockPos(8, 1, 5).asLong());
            var protectedCleanup = new BuildScaffoldCleanup(world.player, TARGET, forbidden);
            check(!protectedCleanup.ready(), "inherited body protection excludes the current stance");
            var cleanup = new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet());
            check(cleanup.ready(), "unprotected, grounded and visible stance is ready");
            check(!NavigationSafetyContext.withForbiddenBodyCells(List.of(new BlockPos(8, 2, 5)), cleanup::ready),
                    "new protection of the head cell must be applied at the actual break boundary");
            field(Entity.class, "onGround").setBoolean(world.player, false);
            check(!cleanup.ready(), "a falling player must not start cleanup");
            field(Entity.class, "onGround").setBoolean(world.player, true);
            var unloaded = new BuildScaffoldCleanup(world.player, new BlockPos(16, 1, 8), LongSets.emptySet());
            check(!unloaded.ready() && candidate(unloaded) == null && unloaded.exhausted(),
                    "unloaded target information cannot create a ready or candidate stance");
            unchanged(world);
        }
    }

    private static void bodyCollisionAndHazardDoNotBecomeSafeShots() throws Exception {
        try (var world = scene(new Vec3(8.25, 1, 5.5))) {
            world.set(new BlockPos(7, 1, 5), Blocks.STONE.defaultBlockState());
            check(nativeHit(world, TARGET) != null, "a body-side collision need not obstruct the eye ray");
            check(!new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet()).ready(),
                    "a clear ray cannot override a colliding body");
            world.set(new BlockPos(7, 1, 5), Blocks.AIR.defaultBlockState());
            world.position(new Vec3(8.5, 1, 5.5));
            world.set(new BlockPos(8, 0, 5), Blocks.MAGMA_BLOCK.defaultBlockState());
            check(nativeHit(world, TARGET) != null, "hazardous footing does not itself block the ray");
            check(!new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet()).ready(),
                    "hazardous floor cannot be a safe cleanup stance");
            world.set(new BlockPos(8, 0, 5), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(8, 1, 5), Blocks.WATER.defaultBlockState());
            check(!new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet()).ready(),
                    "fluid inside the body cannot be treated as dry standing access");
            unchanged(world);
        }
    }

    private static void partialOutlineMustUseTheSameNativeFaceProbes() throws Exception {
        try (var world = scene(new Vec3(8.5, 1, 5.5))) {
            BlockPos overhang = new BlockPos(8, 2, 7); world.set(overhang, Blocks.OAK_SLAB.defaultBlockState());
            Vec3 eye = world.player.getEyePosition(); Vec3 direction = TARGET.getCenter().subtract(eye).normalize();
            BlockHitResult center = world.level.clip(new ClipContext(eye,
                    eye.add(direction.scale(AimGeometry.blockReachDistance(world.player))), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, world.player));
            check(center.getType() == HitResult.Type.BLOCK && center.getBlockPos().equals(overhang),
                    "the real lower slab blocks the scaffold center ray");
            var cleanup = new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet());
            check(nativeHit(world, TARGET) != null && cleanup.ready(),
                    "a different native face probe reaches below the partial overhang without breaking it");
            world.set(overhang, Blocks.AIR.defaultBlockState());
            world.set(new BlockPos(8, 1, 5), Blocks.OAK_SLAB.defaultBlockState());
            world.position(new Vec3(8.5, 1.5, 5.5));
            check(new BuildScaffoldCleanup(world.player, TARGET, LongSets.emptySet()).ready()
                    && nativeHit(world, TARGET) != null, "fractional slab footing is tested at its actual height");
            unchanged(world);
        }
    }

    private static InteractionWorldTestHarness scene(Vec3 feet) throws Exception {
        var world = new InteractionWorldTestHarness(); world.position(feet);
        // Native reach queries consult cached game mode; no network connection exists in this fixture.
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Object info = memory.allocateInstance(PlayerInfo.class);
        field(PlayerInfo.class, "gameMode").set(info, GameType.SURVIVAL);
        field(AbstractClientPlayer.class, "playerInfo").set(world.player, info);
        field(Entity.class, "dimensions").set(world.player, EntityDimensions.scalable(.6F, 1.8F));
        world.set(TARGET, Blocks.DIRT.defaultBlockState()); return world;
    }
    private static BuildScaffoldCleanup.Candidate candidate(BuildScaffoldCleanup cleanup) {
        for (int ticks = 0; ticks < 2000 && !cleanup.exhausted(); ticks++) {
            BuildScaffoldCleanup.Candidate candidate = cleanup.next(); if (candidate != null) return candidate;
        }
        check(cleanup.exhausted(), "bounded stance scan did not finish"); return null;
    }
    private static BlockHitResult nativeHit(InteractionWorldTestHarness world, BlockPos target) throws Exception {
        var hit = BlockDigger.class.getDeclaredMethod("reachableHit", BlockPos.class); hit.setAccessible(true);
        BlockHitResult result = (BlockHitResult) hit.invoke(new BlockDigger(world.player), target);
        check(result == null || result.getBlockPos().equals(target), "native target-only probe returned an occluder"); return result;
    }
    private static boolean horizontalContact(Vec3 feet, double width, AABB target) {
        return feet.x + width / 2 > target.minX && feet.x - width / 2 < target.maxX
                && feet.z + width / 2 > target.minZ && feet.z - width / 2 < target.maxZ;
    }
    private static Field field(Class<?> type, String name) throws Exception { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
    private static void unchanged(InteractionWorldTestHarness world) {
        check(world.level.getBlockState(TARGET).is(Blocks.DIRT), "planning must retain the target scaffold");
        check(world.blockUses() == 0 && world.itemUses() == 0, "geometry checks must not submit digging or placement actions");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
