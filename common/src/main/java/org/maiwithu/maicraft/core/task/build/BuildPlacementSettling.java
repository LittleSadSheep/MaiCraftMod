// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;

/** Successful placement candidates must survive two physical ticks with the same settled view and facts. */
final class BuildPlacementSettling {
    private record Witness(BuildPlacementGeometry.Gesture gesture, BlockPos target, BlockPos support,
                           Direction face, BlockState supportState, BlockState before, BlockState predicted, boolean sneak) {}
    private final ActualViewConvergenceGate convergence = new ActualViewConvergenceGate();
    private Witness witness;
    private long lastRevision = Long.MIN_VALUE;
    private Vec3 lastLook;
    private int stableTicks;

    boolean ready(LocalPlayer player, BuildPlacementGeometry.Gesture gesture, BlockPos target,
                  BlockHitResult hit, BlockState predicted) {
        long revision = ClientRuntime.requireContext(player).tickRevision();
        Vec3 look = player.getViewVector(1).normalize();
        Vec3 desired = gesture.point().subtract(player.getEyePosition()).normalize();
        Witness current = new Witness(gesture, target, hit.getBlockPos(), hit.getDirection(),
                player.level().getBlockState(hit.getBlockPos()), player.level().getBlockState(target),
                predicted, player.isShiftKeyDown());
        boolean aligned = look.dot(desired) >= Math.cos(Math.toRadians(.1));
        if (!current.equals(witness) || !aligned || lastRevision != Long.MIN_VALUE
                && (revision < lastRevision || revision - lastRevision > 1)) {
            convergence.reset(); stableTicks = 0; lastLook = null; lastRevision = Long.MIN_VALUE;
            witness = current;
        }
        if (!aligned) return false;
        boolean converged = convergence.ready(player, desired);
        if (revision != lastRevision) {
            stableTicks = lastLook != null && look.dot(lastLook) >= Math.cos(Math.toRadians(.05))
                    ? stableTicks + 1 : 1;
            lastLook = look; lastRevision = revision;
        }
        return converged && stableTicks >= 3;
    }
}
