package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.explore.ClientExplorationMemory;

/** 飞行途中积累亲自经过与可见地表群系，复用跑图档案；记录失败不能抢占正在执行的飞控。 */
final class FlightExplorationRecorder {
    private ClientExplorationMemory memory;
    private String unknown;
    private long observed = Long.MIN_VALUE;
    private int column;
    private boolean attempted;
    void tick(LocalPlayer player) {
        try {
            if (!attempted) { attempted = true; memory = new ClientExplorationMemory(player); }
            if (memory == null) return;
            long now = player.level().getGameTime();
            if (now == observed) return;
            observed = now;
            // 身体所在群系按实际到访记录；远处地表只记看见，不能将高空经过算作落地到访。
            memory.tick();
            if (now % 5 != 0) return;
            int index = column++ % 81;
            int x = player.blockPosition().getX() + (index % 9 - 4) * 16;
            int z = player.blockPosition().getZ() + (index / 9 - 4) * 16;
            if (!player.clientLevel.getChunkSource().hasChunk(x >> 4, z >> 4)) return;
            int y = ClientSurfaceHeight.motionBlockingNoLeaves(player.clientLevel, x, z);
            // observeBiome 自带第一人称可见性复核；未加载区块、山体背面与地下群系不凭高度图猜测。
            memory.observeBiome(new BlockPos(x, y, z), false);
        } catch (RuntimeException unavailable) {
            unknown = unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage();
        }
    }
    Map<String,Object> evidence() {
        try {
            if (memory != null) return unknown == null ? memory.receipt()
                    : Map.of("memory", memory.receipt(), "observation_unknown", unknown);
        } catch (RuntimeException unavailable) { unknown = unavailable.toString(); }
        return Map.of("observation_unknown", unknown == null ? "flight observations not started" : unknown);
    }
    void close() {
        // 结束飞行时异步刷入最后一批地点；写盘失败保留未知原因，不改写飞机是否实际着陆的结论。
        if (memory == null) return;
        try { memory.close(); } catch (RuntimeException unavailable) { unknown = unavailable.toString(); }
    }
}
