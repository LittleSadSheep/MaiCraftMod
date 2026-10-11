// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * Simulated（Create Aeronautics 一套里的载具部件：物理组装器、无线红石打字机、舵轮、油门拉杆）的联动入口。
 * 交给登记表：用物理组装器组装与拆回载具的办法，接管无线红石打字机试按的驾驶办法，读载具的座位、回路与姿态。
 * 组装出来的是 Sable 的子世界，所以这里也读 Sable 的结构（和 Sable 的联动用同一个读端，读写端放在一起）。
 * 每一下都经联动入口包一层。
 */
public final class SimulatedCompat extends CompatModule {

    public static final String MOD_ID = "simulated";
    /** 在角色周围多远找结构（格）。 */
    static final int NEAR = 64;

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
        // 坐上驾驶座接管无线红石打字机试按（同一份规格）：要坐下、右键接管、逐条按住再松开，同样进世界时建。
        registry.vehicleDriver(this, services -> new TypewriterDriver(this, services));
        // 读载具（machine_inspect 指 s# 时的 vehicle 一节）：座位、无线红石打字机每个键接到谁、种类、姿态与接地。
        registry.vehicleReader(this, services -> new VehicleReading(this, services));
    }

    /** 角色附近这个编号的物理结构；不在附近为空。 */
    Optional<SableStructures.Structure> structure(ClientLevel level, Player viewer, String id) {
        return structures(level, viewer, viewer.getBoundingBox().inflate(NEAR)).stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst();
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

    /** 无线红石打字机绑了频率的键；见 {@link SimulatedParts#typewriterKeys}。 */
    Set<Integer> typewriterKeys(ClientLevel level, String structureId, BlockPos local) {
        return call("读无线红石打字机绑的键", () -> parts.typewriterKeys(level, structureId, local));
    }

    /** 无线红石打字机每个键往哪对频率发、谁收；见 {@link SimulatedParts#typewriterLinks}。 */
    List<SimulatedParts.KeyLink> typewriterLinks(ClientLevel level, String structureId, BlockPos local,
            List<BlockPos> receivers) {
        return call("读无线红石打字机的回路", () -> parts.typewriterLinks(level, structureId, local, receivers));
    }

    /** 无线红石打字机是不是这个玩家在用；见 {@link SimulatedParts#typewriterUsedBy}。 */
    boolean typewriterUsedBy(ClientLevel level, String structureId, BlockPos local, UUID player) {
        return call("读无线红石打字机谁在用", () -> parts.typewriterUsedBy(level, structureId, local, player));
    }

    /** 按下或松开无线红石打字机的一个键；见 {@link SimulatedParts#typewriterKey}。 */
    void typewriterKey(BlockPos local, int key, boolean press) {
        run(press ? "按下无线红石打字机的键" : "松开无线红石打字机的键", () -> parts.typewriterKey(local, key, press));
    }

    /** 交还无线红石打字机；见 {@link SimulatedParts#typewriterDisconnect}。 */
    void typewriterDisconnect(ClientLevel level, String structureId, BlockPos local) {
        run("交还无线红石打字机", () -> parts.typewriterDisconnect(level, structureId, local));
    }
}
