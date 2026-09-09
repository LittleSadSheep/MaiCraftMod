// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

/** Real native outlines and placement states, using an inert client world without a game window. */
public final class BuildPlacementGestureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var plain : new net.minecraft.world.level.block.Block[]{Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.STONE})
            check(!BuildPlacementInteraction.requiresSneak(plain.defaultBlockState()),
                    "ordinary construction blocks must not force a crouch on every click: " + plain);
        for (var interactive : new net.minecraft.world.level.block.Block[]{Blocks.CRAFTING_TABLE, Blocks.CHEST,
                Blocks.OAK_DOOR, Blocks.NOTE_BLOCK})
            check(BuildPlacementInteraction.requiresSneak(interactive.defaultBlockState()),
                    "native interaction overrides must retain secondary use: " + interactive);
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos at = new BlockPos(4, 1, 4);
            var target = new BuildTaskRecord.Target(Blocks.STONE.defaultBlockState(), Items.STONE,
                    at, "gesture test", null, null, null);
            h.position(new Vec3(3.12, 1, 4.8));
            h.set(at.below(), Blocks.OAK_LOG.defaultBlockState());
            var wood = BuildPlacementGeometry.currentGesture(h.player, target, Map.of());
            check(wood != null && !wood.sneak(), "an adjacent wood support permits a standing placement");
            check(wood.stance().equals(h.player.blockPosition()), "already reachable work retains current feet cell");
            Vec3 standingEye = h.player.position().add(0, h.player.getEyeHeight(Pose.STANDING), 0);
            check(Math.abs(wood.yaw() - AimGeometry.yawTo(standingEye, wood.point())) < .001,
                    "current gesture must aim from actual off-center x/z");
            check(Math.abs(wood.pitch() - AimGeometry.pitchTo(standingEye, wood.point())) < .001,
                    "current gesture must use standing eye height on ordinary supports");
            check(BuildPlacementGeometry.candidateStances(at).contains(at.west()),
                    "one-block neighbor stances must be available to planned routes");

            h.set(at.below(), Blocks.CRAFTING_TABLE.defaultBlockState());
            var table = BuildPlacementGeometry.currentGesture(h.player, target, Map.of());
            check(table != null && table.sneak(), "a crafting table uses crouch to avoid opening its menu");
            Vec3 crouchingEye = h.player.position().add(0, h.player.getEyeHeight(Pose.CROUCHING), 0);
            check(Math.abs(table.pitch() - AimGeometry.pitchTo(crouchingEye, table.point())) < .001,
                    "interactive supports must be predicted from the intended crouching eye");

            h.set(at.below(), Blocks.OAK_SLAB.defaultBlockState());
            var slab = BuildPlacementGeometry.currentGesture(h.player, target, Map.of());
            check(slab != null && slab.face() == Direction.UP && Math.abs(slab.point().y - .4999) < .00001,
                    "bottom slab support must aim at its actual y=.5 top, not the cell's y=1 boundary");
            var shape = h.level.getBlockState(at.below()).getShape(h.level, at.below());
            check(shape.clip(standingEye, slab.point(), at.below()).getDirection() == Direction.UP,
                    "slab gesture must be a real outline ray hit");

            var charged = Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RESPAWN_ANCHOR_CHARGES, 4);
            var exact = new BuildTaskRecord.Target(charged, Items.RESPAWN_ANCHOR,
                    at, "exact native state", null, null, null, false, java.util.Set.of("charges"), true);
            check(BuildPlacementGeometry.currentGesture(h.player, exact, Map.of()) == null,
                    "current-position shortcut must not certify a state a native item use cannot produce");

            h.set(new BlockPos(3, 1, 4), Blocks.STONE.defaultBlockState());
            check(BuildPlacementGeometry.currentGesture(h.player, target, Map.of()) == null,
                    "the shortcut cannot claim a stance occupied by a real block");
            h.set(new BlockPos(3, 1, 4), Blocks.AIR.defaultBlockState());
            h.position(new Vec3(12.1, 1, 4.8));
            check(BuildPlacementGeometry.currentGesture(h.player, target, Map.of()) == null,
                    "a native gesture cannot exceed interaction reach");
        }
        System.out.println("BuildPlacementGestureTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
