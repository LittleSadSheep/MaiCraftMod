// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 接管无线红石打字机开载具：驾驶位是离角色最近的那台无线红石打字机，坐着够得着它的座位里挑最近的一个当驾驶座；
 * 没有这样的座位就站在无线红石打字机旁。角色不在船上、或在船上但够不着驾驶位，先告诉驾驶能力走到驾驶位旁的甲板格。
 */
final class TypewriterDriver implements VehicleDriver {

    /** 座位离无线红石打字机多近（方块中心之间，格）算坐着够得着。 */
    static final double SEAT_REACH = 3.5;

    private final SimulatedCompat compat;
    private final PlayerServices services;

    TypewriterDriver(SimulatedCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 驾驶位：一台无线红石打字机，和坐着够得着它的座位（没有为 null）。地块坐标。 */
    record Station(BlockPos typewriter, BlockPos seat) {

        /** 角色要待的那一格：有座位是座位，没有是无线红石打字机。 */
        BlockPos spot() {
            return seat != null ? seat : typewriter;
        }
    }

    // 已经在这艘船上、离驾驶位够得着就不用走；不然给离驾驶位最近、站得住的甲板格。
    @Override public Optional<WorldPosition> boardAt(String structureId) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null) return Optional.empty();
        Optional<SableStructures.Structure> ship = compat.structure(level, player, structureId);
        if (ship.isEmpty()) return Optional.empty();
        Optional<Station> station = station(level, player, ship.get());
        if (station.isEmpty()) return Optional.empty();
        Vec3 spot = ship.get().pose().toWorld(Vec3.atCenterOf(station.get().spot()));
        if (ship.get().carriesPlayer() && player.getEyePosition().distanceTo(spot) <= StructureBlockUse.REACH - 0.5) {
            return Optional.empty();
        }
        return StructureGeometry.deck(ship.get(), local -> compat.block(level, ship.get().id(), local)).stream()
                .min(Comparator.comparingDouble(cell -> cell.world().distanceToSqr(spot)))
                .map(cell -> new WorldPosition((int) Math.floor(cell.world().x), (int) Math.floor(cell.world().y + 0.01),
                        (int) Math.floor(cell.world().z), level.dimension().location().toString()));
    }

    @Override public Optional<Run> test(TestRequest request) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null) return Optional.empty();
        Optional<SableStructures.Structure> ship = compat.structure(level, player, request.structureId());
        if (ship.isEmpty()) return Optional.empty();
        return station(level, player, ship.get()).map(station -> {
            List<TypewriterTrial.Planned> planned = new ArrayList<>();
            for (Press press : request.inputs()) {
                planned.add(new TypewriterTrial.Planned(press.control(), TypewriterKeys.key(press.control()), press.ticks()));
            }
            return new TypewriterTrial(compat, services, ship.get().id(), station, planned);
        });
    }

    // 离角色最近的无线红石打字机，和坐着够得着它的座位里最近的一个。
    private Optional<Station> station(ClientLevel level, LocalPlayer player, SableStructures.Structure ship) {
        StructureGeometry.Scan scan = compat.scan(level, ship);
        Vec3 eye = player.getEyePosition();
        return scan.partsOf(StructureGeometry.TYPEWRITER).stream()
                .min(Comparator.comparingDouble(local -> ship.pose().toWorld(Vec3.atCenterOf(local)).distanceToSqr(eye)))
                .map(typewriter -> new Station(typewriter, scan.seats().stream()
                        .filter(seat -> Vec3.atCenterOf(seat).distanceTo(Vec3.atCenterOf(typewriter)) <= SEAT_REACH)
                        .min(Comparator.comparingDouble(seat -> seat.distSqr(typewriter))).orElse(null)));
    }
}
