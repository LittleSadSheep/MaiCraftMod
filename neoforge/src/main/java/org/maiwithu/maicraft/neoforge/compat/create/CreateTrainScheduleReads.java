// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import net.minecraft.server.level.ServerLevel;

import org.maiwithu.maicraft.server.compat.create.CreateTrainReads;

/**
 * 在服务端读机械动力列车时刻表的读写端：服务端铁路管理器里有时刻表、且有车厢在这个维度的车；
 * 时刻表里只收"前往车站"那一种项，站名过滤原样带上，再带上 Create 自己换成的正则。只翻译，不判断。
 */
public final class CreateTrainScheduleReads implements CreateTrainReads {

    @Override public List<TrainSchedule> trains(ServerLevel level) {
        List<TrainSchedule> found = new ArrayList<>();
        for (Train train : Create.RAILWAYS.trains.values()) {
            Schedule schedule = train.runtime.getSchedule();
            if (schedule == null || !inDimension(train, level)) continue;
            List<Stop> stops = new ArrayList<>();
            for (int index = 0; index < schedule.entries.size(); index++) {
                ScheduleEntry entry = schedule.entries.get(index);
                if (entry.instruction instanceof DestinationInstruction destination) {
                    stops.add(new Stop(index, destination.getFilter(), destination.getFilterForRegex()));
                }
            }
            found.add(new TrainSchedule(train.id.toString(), train.name.getString(), schedule.cyclic,
                    train.runtime.currentEntry, stops));
        }
        return found;
    }

    private static boolean inDimension(Train train, ServerLevel level) {
        for (Carriage carriage : train.carriages) {
            if (carriage.getPresentDimensions().contains(level.dimension())) return true;
        }
        return false;
    }
}
