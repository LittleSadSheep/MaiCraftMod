// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** 覆盖真实地面偏移与施工流程交接；已经能点击的普通站位不再为格心精度重复蹲走，交互方块仍保留副操作。 */
public final class BuildPlacementStandingAccessTest {
    private static final Vec3 ANCHOR = new Vec3(3.5, 1, 3.5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); fullFloorOffset(); reuseCurrentFooting(false); reuseCurrentFooting(true); loweredViewRetainsFullFooting();
        System.out.println("BuildPlacementStandingAccessTest: full-floor offsets, current stance reuse and interaction posture passed");
    }

    private static void fullFloorOffset() throws Exception {
        try (var h = fixture()) {
            var target = target(new BlockPos(8, 1, 3)); var world = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
            var search = new BuildPlacementAccessSearch(h.player, target, world, ANCHOR, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY, 512, true);
            for (int step = 0; step < 500 && !search.advance(16); step++) { }
            check(search.accepted() && !search.access().edge() && !search.access().gesture().sneak(),
                    "a reachable offset on a complete stone platform must not be labelled a crouching edge");
            check(search.access().approach().distanceToSqr(search.access().feet()) <= .7 * .7,
                    "a supported continuous offset retains a reachable anchor instead of demanding navigation to a possibly obstructed cell center");
            check(h.player.position().equals(ANCHOR) && h.blockUses() == 0, "search only proves a stance without moving or clicking");
        }
    }

    private static void reuseCurrentFooting(boolean interactive) throws Exception {
        try (var h = fixture()) {
            var target = target(new BlockPos(5, 1, 3));
            if (interactive) h.set(target.pos().below(), Blocks.CRAFTING_TABLE.defaultBlockState());
            h.position(ANCHOR.add(.6, 0, 0)); Vec3 before = h.player.position();
            var prepared = new AtomicInteger();
            var access = new BuildPlacementAccessSearch.Access(ANCHOR, ANCHOR, List.of(ANCHOR), null, false);
            var drive = new BuildPlacementAccessDrive(h.player, target, PlayerNav.ContextProvider.DEFAULT, () -> true, access,
                    () -> { prepared.incrementAndGet(); return BuildPlacementAccessDrive.Status.READY; });
            BuildPlacementAccessDrive.Status state = BuildPlacementAccessDrive.Status.RUNNING;
            for (int tick = 0; tick < 15 && state == BuildPlacementAccessDrive.Status.RUNNING; tick++) { state = drive.tick(); h.nextTick(); }
            check(state == BuildPlacementAccessDrive.Status.READY && prepared.get() == 1 && h.player.position().equals(before),
                    "already usable full-floor footing does not demand movement to a 0.035-block anchor tolerance");
            check(field(drive, "anchorAlignment").get(drive) == null && drive.gesture().sneak() == interactive,
                    "only the clicked support's actual interaction policy requests sneak in ordinary placement");
            check(drive.evidence().get("posture_reason").equals(interactive ? "right_click_interaction" : "current_supported_click"),
                    "progress distinguishes interaction crouching from ordinary safe footing");
        }
    }

    private static InteractionWorldTestHarness fixture() throws Exception {
        var h = new InteractionWorldTestHarness(); field(h.player, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        h.position(ANCHOR); h.player.setDeltaMovement(0, -.0784, 0); return h;
    }
    private static void loweredViewRetainsFullFooting() throws Exception {
        try (var h = fixture()) {
            var target = target(new BlockPos(5, 1, 3));
            h.set(new BlockPos(4, 2, 3), Blocks.STONE.defaultBlockState());
            var view = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
            check(BuildEdgeMotion.canStandAt(h.player, LongSets.emptySet(), p -> true), "the horizontal beam leaves the current standing body and complete footprint safe");
            check(BuildPlacementGeometry.projectedGestureFrom(h.player, target, view, h.level::isLoaded, ANCHOR, false) == null
                            && BuildPlacementGeometry.projectedGestureFrom(h.player, target, view, h.level::isLoaded, ANCHOR, true) != null,
                    "the beam blocks every standing native click, while lowering the eye proves a legal placement from the same feet");
            var search = new BuildPlacementAccessSearch(h.player, target, view, ANCHOR, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY, 512, true);
            for (int step = 0; step < 500 && !search.advance(16); step++) { }
            check(search.accepted() && !search.access().edge() && search.access().gesture().sneak()
                            && search.access().feet().equals(ANCHOR) && search.reason().equals("reachable_lower_eye_placement_verified"),
                    "a lower-eye proof remains an ordinary supported stance, never a fabricated edge");
            var drive = new BuildPlacementAccessDrive(h.player, target, PlayerNav.ContextProvider.DEFAULT, () -> true, search.access(),
                    () -> BuildPlacementAccessDrive.Status.READY);
            BuildPlacementAccessDrive.Status state = BuildPlacementAccessDrive.Status.RUNNING;
            for (int tick = 0; tick < 15 && state == BuildPlacementAccessDrive.Status.RUNNING; tick++) { state = drive.tick(); h.nextTick(); }
            check(state == BuildPlacementAccessDrive.Status.READY && drive.gesture().sneak()
                            && drive.evidence().get("posture_reason").equals("crouching_view_required") && !drive.edgeActive(),
                    "live replanning retains required eye lowering without starting an edge hold or return");
            check(h.player.position().equals(ANCHOR) && h.blockUses() == 0 && h.itemUses() == 0,
                    "proving a different eye height never moves the actor, consumes materials or performs a trial click");
        }
    }
    private static BuildTaskRecord.Target target(BlockPos at) { return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, at, "standing access", null, null, null); }
    private static Field field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
