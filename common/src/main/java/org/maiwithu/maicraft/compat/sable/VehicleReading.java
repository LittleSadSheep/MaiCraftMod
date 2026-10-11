// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 读一辆 Sable 物理结构载具：座位（坐着够得着无线红石打字机的是驾驶座）、无线红石打字机每个绑了频率的键
 * 接到结构上哪几个收端、收端旁边接着什么执行件、推出来的角色；种类、姿态、接地、谁在驾驶。
 * 回路只追到收端旁边那一格：隔着离合器、齿轮、红石线的说未定，留给试按。外面同频的发射端没读。
 */
final class VehicleReading implements VehicleReader {

    private final SimulatedCompat compat;
    private final PlayerServices services;

    VehicleReading(SimulatedCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<View> read(String structureId) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null) return Optional.empty();
        Optional<SableStructures.Structure> found = compat.structure(level, player, structureId);
        if (found.isEmpty()) return Optional.empty();
        SableStructures.Structure ship = found.get();
        StructureGeometry.Scan scan = compat.scan(level, ship);
        List<BlockPos> typewriters = scan.partsOf(StructureGeometry.TYPEWRITER);
        List<BlockPos> receivers = scan.parts().stream().filter(part -> VehicleParts.RECEIVERS.contains(part.id()))
                .map(StructureGeometry.Part::local).toList();
        boolean complete = scan.complete();
        List<String> notes = new ArrayList<>();
        if (!scan.complete()) notes.add("结构有格没读到（太大或没加载），种类与回路按读到的部分");
        List<Input> inputs = new ArrayList<>();
        boolean litByOther = false;
        for (BlockPos typewriter : typewriters) {
            List<SimulatedParts.KeyLink> links = compat.typewriterLinks(level, ship.id(), typewriter, receivers);
            if (links == null) {
                complete = false;
                notes.add("有一台无线红石打字机读不到绑的键");
                continue;
            }
            if (lit(level, ship.id(), typewriter)
                    && !compat.typewriterUsedBy(level, ship.id(), typewriter, player.getUUID())) {
                litByOther = true;
            }
            for (SimulatedParts.KeyLink link : links) {
                List<String> drives = new ArrayList<>();
                for (BlockPos receiver : link.receivers()) drives.addAll(neighbours(level, ship.id(), receiver));
                VehicleParts.Role role = VehicleParts.role(link.receivers().size(), drives);
                inputs.add(new Input("打字机:" + TypewriterKeys.name(link.key()), link.first() + " + " + link.second(),
                        link.receivers().size(), VehicleParts.grouped(drives), role.role(), role.basis()));
            }
        }
        if (typewriters.size() > 1) notes.add("结构上有 " + typewriters.size() + " 台无线红石打字机，输入名字相同的按离角色最近的那台开");
        if (scan.controls().stream().anyMatch(part -> !part.id().equals(StructureGeometry.TYPEWRITER))) {
            notes.add("舵轮、油门拉杆还不会读回路，也开不了");
        }
        notes.add("外面同频的发射端没读（别人可能在远处遥控）");
        List<Seat> seats = new ArrayList<>();
        Vec3 sitting = player.isPassenger() && ship.carriesPlayer() ? player.position() : null;
        boolean selfDriving = false;
        for (BlockPos seat : scan.seats()) {
            boolean driver = typewriters.stream().anyMatch(typewriter ->
                    Vec3.atCenterOf(seat).distanceTo(Vec3.atCenterOf(typewriter)) <= TypewriterDriver.SEAT_REACH);
            Vec3 at = ship.pose().toWorld(Vec3.atCenterOf(seat));
            if (driver && sitting != null && sitting.distanceTo(at) < 1.5) selfDriving = true;
            seats.add(new Seat(round(at.x), round(at.y), round(at.z), driver));
        }
        boolean onWater = VehicleAttitude.onWater(ship.pose(), scan.boxes(), cell -> !level.isLoaded(cell) ? null
                : !level.getFluidState(cell).isEmpty());
        List<String> ids = scan.parts().stream().map(StructureGeometry.Part::id).toList();
        String kind = VehicleParts.kind(complete, seats.size(), ids, onWater);
        VehicleAttitude.Angles angles = VehicleAttitude.angles(ship.pose());
        String contact = VehicleAttitude.contact(ship.pose(), scan.boxes(), cell -> solidTop(level, cell));
        State state = new State(StructureGeometry.moving(ship),
                round(StructureGeometry.velocity(ship).length() * 20), round(angles.heading()), round(angles.pitch()),
                round(angles.roll()), contact, selfDriving ? "self" : litByOther ? "other" : "");
        return Optional.of(new View(ship.id(), ship.name(), kind, complete, seats, inputs, state, String.join("；", notes)));
    }

    // 收端旁边六格里认得的执行件。
    private List<String> neighbours(ClientLevel level, String structureId, BlockPos receiver) {
        List<String> found = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockState state = compat.block(level, structureId, receiver.relative(side));
            if (state == null) continue;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (VehicleParts.actuator(id)) found.add(id);
        }
        return found;
    }

    private boolean lit(ClientLevel level, String structureId, BlockPos typewriter) {
        BlockState state = compat.block(level, structureId, typewriter);
        return state != null && state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    // 世界里一格顶面的高度（格内）：没有碰撞为 -1，没加载为 null。
    private static Double solidTop(ClientLevel level, BlockPos cell) {
        if (!level.isLoaded(cell)) return null;
        VoxelShape shape = level.getBlockState(cell).getCollisionShape(level, cell);
        return shape.isEmpty() ? -1.0 : shape.max(Direction.Axis.Y);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
