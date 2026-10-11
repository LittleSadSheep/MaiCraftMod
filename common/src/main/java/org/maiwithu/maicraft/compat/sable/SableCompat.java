// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * Sable（物理结构）的联动入口：飞艇、船、车这类会动的方块结构。交给登记表两样——寻路的障碍来源
 * （结构的逐格碰撞换到世界、装在上面的螺旋桨这类装置也算，自己站着的那艘不当墙），
 * 和观察（物理结构领 s#：名字、动没动、几格方块、几个座位、是不是载具）。每一下读都经联动入口包一层。
 *
 * <p>逐格读一艘船要读很多格：读一次按地块坐标记下来（和位姿无关），两百刻后再重读，换位姿只换算不重读。
 */
public final class SableCompat extends CompatModule {

    public static final String MOD_ID = "sable";
    /** 逐格读的结果多久重读一次（刻）：方块改了（加配重、被炸）这么久以内会看出来。 */
    static final int SCAN_FRESH_TICKS = 200;

    private final SableStructures structures;
    private final Map<String, Scanned> scans = new HashMap<>();

    private record Scanned(long at, StructureGeometry.Scan scan) {}

    public SableCompat(SableStructures structures) {
        super(MOD_ID, "Sable 物理结构");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 会动的船、飞艇当寻路障碍；观察里列物理结构（s#）。
        registry.obstacleSource(this, new SableObstacles(this));
        registry.modSight(this, new SableSight(this));
    }

    /** 和一个区域沾边的物理结构；见 {@link SableStructures#near}。 */
    public List<SableStructures.Structure> structures(ClientLevel level, Player viewer, AABB region) {
        return call("读物理结构", () -> structures.near(level, viewer, region));
    }

    /** 结构地块里一格的方块；见 {@link SableStructures#block}。 */
    public BlockState block(ClientLevel level, String id, BlockPos local) {
        return call("读物理结构的方块", () -> structures.block(level, id, local));
    }

    /** 装在结构上的装置此刻在世界里的范围；见 {@link SableStructures#attachedEntities}。 */
    public List<AABB> attachedEntities(ClientLevel level, String id) {
        return call("读物理结构上的装置", () -> structures.attachedEntities(level, id));
    }

    /** 一个结构的逐格读结果：两百刻内复用，过了重读；不在了的结构顺手丢掉。 */
    StructureGeometry.Scan scan(ClientLevel level, SableStructures.Structure structure) {
        long now = level.getGameTime();
        Scanned cached = scans.get(structure.id());
        if (cached != null && now - cached.at() < SCAN_FRESH_TICKS) return cached.scan();
        StructureGeometry.Scan scan = StructureGeometry.scan(structure, local -> block(level, structure.id(), local));
        scans.entrySet().removeIf(entry -> now - entry.getValue().at() > SCAN_FRESH_TICKS * 10L);
        scans.put(structure.id(), new Scanned(now, scan));
        return scan;
    }
}
