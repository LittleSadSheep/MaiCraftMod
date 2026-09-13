// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometry replays use the installed Mek small-cable dimensions; native gameplay is validated separately. */
public final class MekanismTransmitterAimTest {
    private static final BlockPos TARGET = BlockPos.ZERO;
    private static final VoxelShape CENTER = Shapes.box(5.0/16, 5.0/16, 5.0/16, 11.0/16, 11.0/16, 11.0/16);
    private static final VoxelShape WEST = Shapes.box(0, 5.0/16, 5.0/16, 5.0/16, 11.0/16, 11.0/16);
    private static final VoxelShape SOURCE = Shapes.create(new AABB(-1, 0, 0, 0, 1, 1));
    private record Selection(Vec3 aim, Direction surface, Direction connection) {}

    public static void main(String[] args) {
        absentArmCanBeConfiguredBesideSolidSource();
        sideSurfaceSelectsExistingArm();
        occlusionAndAmbiguousGeometryRemainRejected();
        System.out.println("MekanismTransmitterAimTest: absent-arm fallback, side-surface selection and occlusion passed");
    }
    private static void absentArmCanBeConfiguredBesideSolidSource() {
        Vec3 eye = new Vec3(-.5, 1.27, -1.5);
        check(trace(new Vec3(.1, .5, .5), eye, List.of(CENTER), 0, SOURCE) == null,
                "the old single arm point is blocked by the solid source while that arm is absent");
        Selection selected = select(eye, List.of(CENTER), 0, SOURCE);
        check(selected != null && selected.connection == Direction.WEST && selected.surface == Direction.WEST,
                "a visible center-west surface must configure the missing west arm through native hit-face fallback");
        check(selected.aim.x > 0 && selected.aim.x < .5, "aim stays on the cable, not inside the neighboring source block");
    }
    private static void sideSurfaceSelectsExistingArm() {
        Vec3 eye = new Vec3(1.5, 1.27, -1.5);
        int mask = 1 << Direction.WEST.ordinal();
        Selection selected = select(eye, List.of(WEST, CENTER), mask, SOURCE);
        check(selected != null && selected.connection == Direction.WEST, "native multipart selection must retain the west arm");
        check(selected.surface != Direction.WEST, "viewing an arm from above/north is a valid west connection selection");
        check(MekanismInteractionGeometry.selectedFace(-1, mask, Direction.UP) == Direction.WEST,
                "an existing arm takes precedence over the outer collision-face direction");
        check(MekanismInteractionGeometry.selectedFace(0, mask, Direction.UP) == Direction.UP,
                "only a center hit falls back to the actual collision face");
    }
    private static void occlusionAndAmbiguousGeometryRemainRejected() {
        Vec3 eye = new Vec3(-.5, 1.27, -1.5);
        VoxelShape wall = Shapes.create(new AABB(-5, -5, -.2, 5, 5, 0));
        check(select(eye, List.of(CENTER), 0, wall) == null, "a wall must not become a synthetic click through solid blocks");
        check(MekanismInteractionGeometry.transmitterSamples(TARGET, Direction.WEST, eye, List.of(CENTER), 1 << Direction.WEST.ordinal()).isEmpty(),
                "mismatched native arm mask and shapes cannot authorize guessed aiming");
        check(MekanismInteractionGeometry.selectedFace(12, 0, Direction.WEST) == null, "out-of-range segment index cannot become fallback permission");
    }
    private static Selection select(Vec3 eye, List<VoxelShape> shapes, int mask, VoxelShape obstacle) {
        for (Vec3 aim : MekanismInteractionGeometry.transmitterSamples(TARGET, Direction.WEST, eye, shapes, mask)) {
            Selection selection = trace(aim, eye, shapes, mask, obstacle);
            if (selection != null && selection.connection == Direction.WEST) return selection;
        }
        return null;
    }
    private static Selection trace(Vec3 aim, Vec3 eye, List<VoxelShape> shapes, int mask, VoxelShape obstacle) {
        Vec3 end = eye.add(aim.subtract(eye).normalize().scale(4.5));
        VoxelShape combined = Shapes.empty(); for (VoxelShape shape : shapes) combined = Shapes.or(combined, shape);
        BlockHitResult hit = combined.clip(eye, end, TARGET), blocked = obstacle.clip(eye, end, TARGET);
        if (hit == null || blocked != null && blocked.getLocation().distanceToSqr(eye) <= hit.getLocation().distanceToSqr(eye)) return null;
        double closest = Double.POSITIVE_INFINITY; int subHit = Integer.MAX_VALUE;
        for (int index = 0; index < shapes.size(); index++) {
            var part = shapes.get(index).clip(eye, end, TARGET);
            if (part != null && part.getLocation().distanceToSqr(eye) < closest) {
                closest = part.getLocation().distanceToSqr(eye); subHit = index - 1; // MultipartUtils native indexing starts at -1.
            }
        }
        return new Selection(aim, hit.getDirection(), MekanismInteractionGeometry.selectedFace(subHit, mask, hit.getDirection()));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
