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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Predicate;

/** 有界本地动力来源发现：不会移动角色、强制加载区块或跨 tick 保留实时映射迭代器。 */
final class KineticSourceDiscovery {
    private final BlockPos target;
    private final int radius;
    private final double minimumRpm;
    private final List<int[]> chunks = new ArrayList<>();
    private final List<KineticNativeView.Observation> found = new ArrayList<>();
    private Iterator<BlockPos> active;
    private int chunkIndex, samples;
    private final Vec3 observer;
    private final int workY;
    private final Predicate<BlockState> filter;
    KineticSourceDiscovery(BlockPos target, int radius, double minimumRpm) {
        this(target, radius, minimumRpm, Vec3.atCenterOf(target).add(0, 1, 0), target.getY(), state -> true);
    }
    /** 来源搜索共享身体所在层与可见范围，过滤注册名不会扩大这份现场权限。 */
    KineticSourceDiscovery(BlockPos target, int radius, double minimumRpm, Vec3 observer, int workY, Predicate<BlockState> filter) {
        this.target=target.immutable(); this.radius=radius; this.minimumRpm=minimumRpm;
        this.observer=observer; this.workY=workY; this.filter=filter;
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
            if(at.equals(target) || !withinHorizontalRadius(at) || NavigationSafetyContext.protectsUse(at)) continue;
            if (Math.abs((long) at.getY() - workY) > KineticSourceScope.HEIGHT_DELTA || !filter.test(level.getBlockState(at))) continue;
            // 先确认属于可见作业范围，再读取网络和转速；隔墙的机器不进入动力证据采集。
            if (!KineticNativeView.kinetic(level, at) || !KineticSourceScope.allows(level, observer, workY, at)) continue;
            var value=KineticNativeView.read(level,at,null,true);
            if(value!=null && value.powered() && Math.abs(value.rpm())>=minimumRpm) {
                found.add(value); found.sort(Comparator.comparingDouble(v -> v.endpoint().position().distSqr(target)));
                if(found.size()>8) found.removeLast();
            }
        }
        return samples>=8192;
    }
    List<KineticNativeView.Observation> sources(ClientLevel level) {
        // 跨 tick 扫描后再次核对可见性和真实供电，已经停转、遮挡或受保护的旧候选不能继续用于接线。
        return found.stream().filter(value -> KineticSourceScope.allows(level, observer, workY, value.endpoint().position()))
                .flatMap(value -> KineticNativeView.variants(level,value.endpoint().position(),null,true).stream())
                .filter(value -> value.powered() && Math.abs(value.rpm()) >= minimumRpm).toList();
    }
    int samples() { return samples; }
    /** 水平范围与楼层分开约束，扩大搜索半径不会连带深入地底。 */
    private boolean withinHorizontalRadius(BlockPos at) {
        double x=(long)at.getX()-target.getX(), z=(long)at.getZ()-target.getZ();
        return x*x+z*z<=(double)radius*radius;
    }
}
