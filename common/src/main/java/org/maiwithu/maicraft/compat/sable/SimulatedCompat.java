// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * Simulated（Create Aeronautics 一套里的载具部件：物理组装器、无线红石打字机、舵轮、油门拉杆）的联动入口。
 * 交给登记表：用物理组装器组装与拆回载具的办法。组装出来的是 Sable 的子世界，所以这里也读 Sable 的结构
 * （和 Sable 的联动用同一个读端，读写端放在一起）。每一下都经联动入口包一层。
 */
public final class SimulatedCompat extends CompatModule {

    public static final String MOD_ID = "simulated";

    private final SimulatedParts parts;
    private final SableStructures structures;
    private final StructureScans scans = new StructureScans();

    public SimulatedCompat(SimulatedParts parts, SableStructures structures) {
        super(MOD_ID, "Simulated 载具部件");
        this.parts = Objects.requireNonNull(parts, "parts");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 物理组装器拉一次杆组装或拆回（所有者 10-11 批的载具规格）：要走过去、拉、确认，进世界时带着玩家行为建。
        registry.vehicleAssembler(this, services -> new PhysicsAssembly(this, services));
    }

    /** 和一个区域沾边的物理结构；见 {@link SableStructures#near}。 */
    List<SableStructures.Structure> structures(ClientLevel level, Player viewer, AABB region) {
        return call("读物理结构", () -> structures.near(level, viewer, region));
    }

    /** 结构地块里一格的方块；见 {@link SableStructures#block}。 */
    BlockState block(ClientLevel level, String id, BlockPos local) {
        return call("读物理结构的方块", () -> structures.block(level, id, local));
    }

    /** 一个结构的逐格读结果；见 {@link StructureScans}。 */
    StructureGeometry.Scan scan(ClientLevel level, SableStructures.Structure structure) {
        return scans.scan(level.getGameTime(), structure, local -> block(level, structure.id(), local));
    }

    /** 这个结构下次重读。 */
    void forget(String id) {
        scans.forget(id);
    }

    /** 拉一次物理组装器的杆；见 {@link SimulatedParts#pullAssembler}。 */
    void pullAssembler(BlockPos at) {
        run("拉物理组装器的杆", () -> parts.pullAssembler(at));
    }
}
