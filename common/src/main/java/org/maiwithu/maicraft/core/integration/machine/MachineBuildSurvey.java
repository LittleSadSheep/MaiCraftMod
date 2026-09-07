// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Incremental whole-plan bounds and multipart-site preflight before any construction changes. */
final class MachineBuildSurvey {
    record Progress(boolean complete, BlockPos needsLoad, String failure) {}
    private final MachineConstructionPlan plan;
    private final List<BlockPos> positions;
    private final Set<BlockPos> centers = new HashSet<>(), clears = new HashSet<>();
    private final Set<BlockPos> openings = new HashSet<>();
    private final List<org.maiwithu.maicraft.core.task.build.BuildTaskRecord.Target> sealTargets;
    private int boundsIndex, partIndex;
    private int sealIndex;

    MachineBuildSurvey(MachineConstructionPlan plan) {
        this.plan = plan; positions = plan.positions();
        sealTargets = plan.seals().stream().flatMap(seal -> seal.targets().stream()).toList();
        plan.parts().stream().filter(part -> part.spec().side() == null).forEach(part -> centers.add(part.position()));
    }

    Progress tick(Level world) {
        int budget = 128;
        while (boundsIndex < positions.size() && budget-- > 0) {
            BlockPos at = positions.get(boundsIndex++);
            if (world.isOutsideBuildHeight(at) || !world.getWorldBorder().isWithinBounds(at))
                return new Progress(false, null, "The compiled installation crosses the world's build boundary.");
        }
        if (boundsIndex < positions.size()) return new Progress(false, null, null);
        while (partIndex < plan.parts().size() && budget-- > 0) {
            var part = plan.parts().get(partIndex); BlockPos at = part.position();
            if (!world.isLoaded(at)) return new Progress(false, at, null);
            var state = world.getBlockState(at);
            boolean matches = MachineInstallation.matches(world, at, part.spec());
            if (!matches && NavigationSafetyContext.protectsMutation(at))
                return new Progress(false, null, "A protected area occupies a planned native part site.");
            boolean futureHost = state.isAir() && part.spec().side() != null && centers.contains(at);
            if (!futureHost && !MachineInstallation.canInstall(world, at, part.spec())) {
                if (!state.isAir() && plan.replaceExisting() && !state.hasBlockEntity()
                        && !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && !state.hasProperty(BlockStateProperties.BED_PART) && state.getDestroySpeed(world, at) >= 0) {
                    MachineBlueprint.requireModeledEffects(state.getBlock()); clears.add(at);
                } else return new Progress(false, null, "An AE2 part site is occupied or its native slot is incompatible.");
            }
            partIndex++;
        }
        if (partIndex < plan.parts().size()) return new Progress(false, null, null);
        while (sealIndex < sealTargets.size() && budget-- > 0) {
            var target = sealTargets.get(sealIndex); BlockPos at = target.pos();
            if (!world.isLoaded(at)) return new Progress(false, at, null);
            var state = world.getBlockState(at);
            if (state.isAir() && !NavigationSafetyContext.protectsMutation(at)) openings.add(at);
            else if (!target.matches(state)) return new Progress(false, null, "A temporary machine entrance is occupied or protected.");
            sealIndex++;
        }
        return new Progress(sealIndex == sealTargets.size(), null, null);
    }

    List<BlockPos> partClears() { return new ArrayList<>(clears); }
    Set<BlockPos> openings() { return Set.copyOf(openings); }
}
