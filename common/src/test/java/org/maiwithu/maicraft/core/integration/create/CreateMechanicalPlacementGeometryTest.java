// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Replays source/receiver stance geometry using an explicitly supplied native preferred-axis fact. */
public final class CreateMechanicalPlacementGeometryTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var border = Level.class.getDeclaredField("worldBorder"); border.setAccessible(true); border.set(h.level, new WorldBorder());
            BlockPos source = new BlockPos(4, 2, 4), routeStart = source.above();
            h.set(source.below(), Blocks.STONE.defaultBlockState()); h.set(source, Blocks.END_ROD.defaultBlockState());
            var before = CreateMechanicalPlanner.findPlacementStand(h.level, routeStart, Set.of(source, routeStart), false);
            check(before == null, "the old angle-only rule rejects this two-block-high endpoint on flat ground");
            var after = CreateMechanicalPlanner.findPlacementStand(h.level, routeStart, Set.of(source, routeStart), true);
            check(after != null && after.getY() == 1, "native vertical-axis inheritance must admit a safe adjacent ground stance");
            check(CreateMechanicalPlacementGeometry.clearForJump(h.level, after), "the selected endpoint stance must leave jump headroom");
            Vec3 top = Vec3.atCenterOf(source).add(0, .5, 0), eye = Vec3.atBottomCenterOf(after).add(0, 1.62, 0);
            check(CreateMechanicalPlacementGeometry.needsTopFaceJump(eye, top, Direction.UP), "the standing eye cannot see a higher top face");
            check(!CreateMechanicalPlacementGeometry.needsTopFaceJump(eye.add(0, .75, 0), top, Direction.UP), "a real airborne eye may expose the same exact top face");
            check(!CreateMechanicalPlacementGeometry.needsTopFaceJump(eye, top, Direction.EAST), "horizontal supports do not need endpoint top-face jumping");
            h.set(after.above(3), Blocks.STONE.defaultBlockState());
            check(!CreateMechanicalPlacementGeometry.clearForJump(h.level, after), "a low roof must block native jump placement");
            BlockPos middle = routeStart.east(2), beneath = new BlockPos(middle.getX(), 1, middle.getZ());
            check(CreateMechanicalPlacementGeometry.feasibleView(beneath, middle, false), "standing below an intermediate elevated chain cell provides a real vertical view");
            check(!CreateMechanicalPlacementGeometry.feasibleView(beneath.east(1), middle, false), "an arbitrary side stance cannot invent a vertical axis without native inheritance");
            check(h.blockUses() == 0 && h.itemUses() == 0, "route and jump checks remain read-only");
        }
        System.out.println("CreateMechanicalPlacementGeometryTest: native-axis stance, real top-face jump and roof protection passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
