// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** One planning tick's loaded terrain cache; discarded before construction revalidation. */
final class KineticPlanningTerrain implements KineticRouteGeometry.Terrain {
    private final ClientLevel level;
    private final Map<BlockPos,BlockState> states=new HashMap<>();
    KineticPlanningTerrain(ClientLevel level) { this.level=level; }
    public boolean loaded(BlockPos at) { return !level.isOutsideBuildHeight(at) && level.getWorldBorder().isWithinBounds(at) && level.isLoaded(at); }
    private BlockState state(BlockPos at) {
        if(!loaded(at)) throw new IllegalArgumentException("kinetic_planning_chunk_unloaded");
        return states.computeIfAbsent(at.immutable(),level::getBlockState);
    }
    public boolean passable(BlockPos at) { return loaded(at) && state(at).isAir() && state(at).getFluidState().isEmpty(); }
    public boolean protectedCell(BlockPos at) { return NavigationSafetyContext.protectsMutation(at) || NavigationSafetyContext.forbidsBody(at); }
    public boolean kinetic(BlockPos at) { return KineticNativeView.kinetic(level,at); }
    public Integer groundHeight(int x,int z) {
        var chunk=level.getChunkSource().getChunk(x>>4,z>>4,ChunkStatus.FULL,false);
        return chunk==null ? null : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15);
    }
}
