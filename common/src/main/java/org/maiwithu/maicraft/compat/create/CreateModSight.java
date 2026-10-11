// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 机械动力的东西在观察里的样子：电梯（编号 b#，按最低一层的触点认；当前层、几层、在不在动，详情给全部楼层与相对高度），
 * 车站（b#；站名、有没有车在站），列车（e#，一趟车列最近的那节车厢；车名、动没动、几节、几个空座）。
 */
final class CreateModSight implements ModSight {

    private final CreateCompat compat;

    CreateModSight(CreateCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        elevators(level, viewer, region, sink);
        stations(level, region, sink);
        trains(level, viewer, region, sink);
    }

    // 电梯：楼层表同步了才列楼层；最低一层的触点当编号的位置，轿厢上下动编号也不变。
    private void elevators(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        for (CreateElevators.Elevator elevator : compat.elevators(level, viewer, region)) {
            if (elevator.floors().isEmpty()) continue;
            CreateElevators.Floor lowest = elevator.floors().getFirst();
            JsonObject summary = new JsonObject();
            elevator.floors().stream().filter(floor -> floor.contactY() == elevator.currentContactY()).findFirst()
                    .ifPresent(floor -> summary.addProperty("current_floor", ElevatorFloors.describe(floor)));
            summary.addProperty("floors", elevator.floors().size());
            summary.addProperty("moving", !elevator.arrived());
            if (!elevator.arrived()) {
                elevator.floors().stream().filter(floor -> floor.contactY() == elevator.targetContactY()).findFirst()
                        .ifPresent(floor -> summary.addProperty("going_to", ElevatorFloors.describe(floor)));
            }
            JsonArray floors = new JsonArray();
            for (CreateElevators.Floor floor : elevator.floors()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", ElevatorFloors.describe(floor));
                if (!floor.description().isBlank()) entry.addProperty("description", floor.description());
                entry.addProperty("contact_y", floor.contactY());
                entry.addProperty("landing_y", floor.landingY());
                entry.addProperty("relative_y", floor.landingY() - viewer.blockPosition().getY());
                floors.add(entry);
            }
            JsonObject detail = new JsonObject();
            detail.add("floor_list", floors);
            BlockPos at = elevator.contact(lowest);
            sink.accept(new ModThing("elevator", ModThing.Identity.BLOCK, "", position(level, at), summary, detail));
        }
    }

    // 车站：站名；车站方块给比较器的信号满格就是有车停在站里。
    private void stations(ClientLevel level, AABB region, Consumer<ModThing> sink) {
        for (CreateRailways.Station station : compat.stations(level)) {
            if (!region.contains(station.pos().getCenter()) || !level.isLoaded(station.pos())) continue;
            JsonObject summary = new JsonObject();
            summary.addProperty("name", station.name());
            summary.addProperty("train_present",
                    level.getBlockState(station.pos()).getAnalogOutputSignal(level, station.pos()) >= 15);
            sink.accept(new ModThing("station", ModThing.Identity.BLOCK, "", position(level, station.pos()), summary, null));
        }
    }

    // 列车：一趟车只列离角色最近的那节车厢；车名、动没动、看得到几节、一共几个空座。
    private void trains(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        Map<String, List<CreateRailways.Car>> byTrain = new LinkedHashMap<>();
        for (CreateRailways.Car car : compat.trainCars(level, viewer, region)) {
            byTrain.computeIfAbsent(car.trainId(), id -> new ArrayList<>()).add(car);
        }
        byTrain.forEach((trainId, cars) -> {
            CreateRailways.Car nearest = cars.stream()
                    .min(Comparator.comparingDouble(car -> car.box().getCenter().distanceToSqr(viewer.position())))
                    .orElseThrow();
            JsonObject summary = new JsonObject();
            summary.addProperty("name", nearest.trainName());
            summary.addProperty("moving", cars.stream().noneMatch(CreateRailways.Car::still));
            summary.addProperty("carriages", cars.size());
            summary.addProperty("free_seats", cars.stream().mapToInt(car -> car.freeSeats().size()).sum());
            summary.addProperty("riding", cars.stream().anyMatch(CreateRailways.Car::carriesPlayer));
            sink.accept(new ModThing("train", ModThing.Identity.ENTITY, Integer.toString(nearest.entityId()),
                    position(level, BlockPos.containing(nearest.box().getCenter())), summary, null));
        });
    }

    private static WorldPosition position(ClientLevel level, BlockPos at) {
        return new WorldPosition(at.getX(), at.getY(), at.getZ(), level.dimension().location().toString());
    }
}
