// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Bounded local source discovery: no travel, forced chunks or live map iterators retained between ticks. */
final class KineticSourceDiscovery {
    private final BlockPos target;
    private final int radius;
    private final double minimumRpm;
    private final List<int[]> chunks = new ArrayList<>();
    private final List<KineticNativeView.Observation> found = new ArrayList<>();
    private Iterator<BlockPos> active;
    private int chunkIndex, samples;
    KineticSourceDiscovery(BlockPos target, int radius, double minimumRpm) {
        this.target=target.immutable(); this.radius=radius; this.minimumRpm=minimumRpm;
        int reach=(radius+15)/16;
        for(int x=-reach;x<=reach;x++) for(int z=-reach;z<=reach;z++) chunks.add(new int[]{x,z});
        chunks.sort(Comparator.comparingInt(p -> p[0]*p[0]+p[1]*p[1]));
    }
    boolean tick(ClientLevel level) {
        int read=0,opened=0;
        while(read<64 && samples<8192) {
            if(active==null) {
                if(chunkIndex>=chunks.size()) return true;
                if(opened++>=2) return false;
                int[] offset=chunks.get(chunkIndex++);
                var chunk=level.getChunkSource().getChunk((target.getX()>>4)+offset[0],(target.getZ()>>4)+offset[1],ChunkStatus.FULL,false);
                if(chunk==null) continue;
                active=chunk.getBlockEntities().keySet().stream().map(BlockPos::immutable).toList().iterator();
            }
            if(!active.hasNext()) { active=null; continue; }
            BlockPos at=active.next(); read++; samples++;
            if(at.equals(target) || at.distSqr(target)>(double)radius*radius || NavigationSafetyContext.protectsUse(at)) continue;
            var value=KineticNativeView.read(level,at,null,true);
            if(value!=null && value.powered() && Math.abs(value.rpm())>=minimumRpm) {
                found.add(value); found.sort(Comparator.comparingDouble(v -> v.endpoint().position().distSqr(target)));
                if(found.size()>8) found.removeLast();
            }
        }
        return samples>=8192;
    }
    List<KineticNativeView.Observation> sources(ClientLevel level) {
        return found.stream().flatMap(value -> KineticNativeView.variants(level,value.endpoint().position(),null,true).stream()).toList();
    }
}
