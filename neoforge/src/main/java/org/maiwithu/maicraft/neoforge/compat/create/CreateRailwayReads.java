// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.sync.ContraptionInteractionPacket;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEditPacket;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.schedule.condition.IdleCargoCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.condition.StationPoweredCondition;
import com.simibubi.create.content.trains.schedule.condition.StationUnloadedCondition;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlock;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.station.StationEditPacket;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.compat.create.CreateRailways;

/**
 * 读机械动力铁路的读写端（客户端）：客户端自己那份铁路管理器里的轨道图与车站（站名、车站方块位置与维度）；
 * 近处的车厢装置实体：属于哪趟车、这一刻挪没挪、座位表里哪些座位没人坐、角色是不是坐在上面；
 * 坐座位发 Create 自己的装置交互包（和玩家对着座位右键时发的一样）；车站的组车模式、组车、拆车发车站界面的编辑包；
 * 车站组车没成的原因从车站同步过来的数据里读；操纵台座位上的生物按车厢装置的座位表与列车长座认；
 * 时刻表读主手那张的数据、写的时候发时刻表界面关上时的那个包。只翻译，不判断。
 */
public final class CreateRailwayReads implements CreateRailways {

    /** 每刻挪不到这么远（格）当它停着。 */
    private static final double STILL = 1e-3;

    @Override public List<Station> stations(ClientLevel level) {
        List<Station> found = new ArrayList<>();
        String here = level.dimension().location().toString();
        for (TrackGraph graph : Create.RAILWAYS.sided(level).trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station.blockEntityPos == null || station.blockEntityDimension == null) continue;
                String dimension = station.blockEntityDimension.location().toString();
                if (dimension.equals(here)) found.add(new Station(station.name, station.blockEntityPos, dimension));
            }
        }
        return found;
    }

    @Override public List<Car> cars(ClientLevel level, Player viewer, AABB region) {
        List<Car> found = new ArrayList<>();
        for (CarriageContraptionEntity car : level.getEntitiesOfClass(CarriageContraptionEntity.class, region,
                Entity::isAlive)) {
            Train train = car.trainId == null ? null : Create.RAILWAYS.sided(level).trains.get(car.trainId);
            Contraption contraption = car.getContraption();
            List<Seat> free = new ArrayList<>();
            if (contraption != null) {
                // 座位表里有人坐的座位按序号记着；没在里面的就是空着。
                Set<Integer> taken = new HashSet<>(contraption.getSeatMapping().values());
                List<BlockPos> seats = contraption.getSeats();
                for (int index = 0; index < seats.size(); index++) {
                    if (taken.contains(index)) continue;
                    BlockPos local = seats.get(index);
                    free.add(new Seat(local, car.toGlobalVector(Vec3.atCenterOf(local), 1)));
                }
            }
            boolean still = car.position().subtract(car.xo, car.yo, car.zo).length() <= STILL;
            found.add(new Car(car.getId(), car.trainId == null ? "" : car.trainId.toString(),
                    train == null ? "" : train.name.getString(), car.getBoundingBox(), still, free,
                    viewer != null && car.hasPassenger(viewer)));
        }
        return found;
    }

    @Override public void sit(int entityId, BlockPos seat) {
        CatnipServices.NETWORK.sendToServer(new ContraptionInteractionPacket(InteractionHand.MAIN_HAND, entityId, seat,
                Direction.UP));
    }

    @Override public StationState station(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof StationBlockEntity station)) return null;
        BlockState state = level.getBlockState(pos);
        boolean assembling = state.hasProperty(StationBlock.ASSEMBLING) && state.getValue(StationBlock.ASSEMBLING);
        // 组车没成的原因存在车站自己的数据里（组车界面就显示它），从同步过来的那份数据里读。
        AssemblyException failure = AssemblyException.read(station.saveWithoutMetadata(level.registryAccess()),
                level.registryAccess());
        return new StationState(assembling, failure == null ? "" : failure.component.getString());
    }

    @Override public void assemblyMode(BlockPos pos, boolean on) {
        CatnipServices.NETWORK.sendToServer(StationEditPacket.configure(pos, on, null, null));
    }

    @Override public void assembleTrain(BlockPos pos) {
        CatnipServices.NETWORK.sendToServer(StationEditPacket.tryAssemble(pos));
    }

    @Override public void disassembleTrain(BlockPos pos) {
        CatnipServices.NETWORK.sendToServer(StationEditPacket.tryDisassemble(pos));
    }

    @Override public List<Integer> conductors(ClientLevel level, int carEntityId) {
        List<Integer> found = new ArrayList<>();
        if (!(level.getEntity(carEntityId) instanceof CarriageContraptionEntity car)) return found;
        if (!(car.getContraption() instanceof CarriageContraption contraption)) return found;
        // 乘客在座位表里按序号记着；坐的那个座位是操纵台座位（列车长座）的才算，玩家不算。
        for (Entity passenger : car.getPassengers()) {
            if (!(passenger instanceof LivingEntity) || passenger instanceof Player) continue;
            Integer index = contraption.getSeatMapping().get(passenger.getUUID());
            if (index == null || index < 0 || index >= contraption.getSeats().size()) continue;
            if (contraption.conductorSeats.containsKey(contraption.getSeats().get(index))) found.add(passenger.getId());
        }
        return found;
    }

    @Override public Timetable heldTimetable(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ScheduleItem)) return null;
        Schedule schedule = ScheduleItem.getSchedule(player.registryAccess(), held);
        if (schedule == null) return new Timetable(List.of(), true);
        List<Stop> stops = new ArrayList<>();
        for (ScheduleEntry entry : schedule.entries) {
            String station = entry.instruction instanceof DestinationInstruction destination ? destination.getFilter()
                    : entry.instruction.getId().toString();
            List<String> wait = new ArrayList<>();
            if (entry.conditions != null && !entry.conditions.isEmpty()) {
                for (ScheduleWaitCondition condition : entry.conditions.getFirst()) wait.add(describe(condition));
            }
            stops.add(new Stop(station, wait));
        }
        return new Timetable(stops, schedule.cyclic);
    }

    @Override public void writeTimetable(Timetable timetable) {
        List<ScheduleEntry> entries = new ArrayList<>();
        for (Stop stop : timetable.stops()) {
            DestinationInstruction destination = new DestinationInstruction();
            destination.getData().putString("Text", stop.station());
            List<ScheduleWaitCondition> all = new ArrayList<>();
            for (String wait : stop.conditions()) {
                ScheduleWaitCondition condition = condition(wait);
                if (condition != null) all.add(condition);
            }
            List<List<ScheduleWaitCondition>> conditions = new ArrayList<>();
            conditions.add(all);
            entries.add(new ScheduleEntry(destination, conditions));
        }
        CatnipServices.NETWORK.sendToServer(new ScheduleEditPacket(new Schedule(entries, timetable.cyclic(), 0)));
    }

    // 一句等候条件换成 Create 的条件：delay、idle 按秒；powered、unloaded 不带数。
    private static ScheduleWaitCondition condition(String wait) {
        String[] words = wait.trim().split("\\s+");
        return switch (words[0]) {
            case "delay" -> timed(new ScheduledDelay(), words);
            case "idle" -> timed(new IdleCargoCondition(), words);
            case "powered" -> new StationPoweredCondition();
            case "unloaded" -> new StationUnloadedCondition();
            default -> null;
        };
    }

    private static ScheduleWaitCondition timed(TimedWaitCondition condition, String[] words) {
        int seconds = words.length > 1 ? Integer.parseInt(words[1]) : 5;
        condition.getData().putInt("Value", seconds);
        condition.getData().putInt("TimeUnit", TimedWaitCondition.TimeUnit.SECONDS.ordinal());
        return condition;
    }

    // Create 的条件读回成一句：按秒的写秒数，别的写它的 ID。
    private static String describe(ScheduleWaitCondition condition) {
        if (condition instanceof TimedWaitCondition timed) {
            int seconds = timed.getValue() * timed.getUnit().ticksPer / 20;
            return (condition instanceof IdleCargoCondition ? "idle " : condition instanceof ScheduledDelay ? "delay "
                    : condition.getId().getPath() + " ") + seconds;
        }
        if (condition instanceof StationPoweredCondition) return "powered";
        if (condition instanceof StationUnloadedCondition) return "unloaded";
        return condition.getId().toString();
    }
}
