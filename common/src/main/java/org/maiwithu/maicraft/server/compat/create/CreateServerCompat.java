// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.create;

import java.util.List;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * 机械动力（Create）的服务端联动入口：登记只读操作 create.trains——客户端问各趟车时刻表上的站序，
 * 回答每趟车的编号、车名、循环不循环、走到第几项、每一站的站名过滤。没装铁路导航时，坐火车靠它查哪趟车去哪
 * （所有者 10-11 定）。只答角色所在维度的世界里的车，不改时刻表、不动车。
 */
public final class CreateServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "create";
    /** 只读操作的名字：客户端坐火车的报价经它问。 */
    public static final String TRAINS = "create.trains";

    private final CreateTrainReads trains;

    public CreateServerCompat(CreateTrainReads trains) {
        super(MOD_ID, "机械动力");
        this.trains = Objects.requireNonNull(trains, "trains");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        registry.readOperation(this, TRAINS, 1, this::answer);
    }

    // 读角色所在世界里每趟车的时刻表，写成回答。
    private JsonObject answer(ServerPlayer player, JsonObject body) {
        if (!(player.level() instanceof ServerLevel level)) {
            throw ServerOperationException.notApplied("not_loaded", "角色不在服务端的世界里");
        }
        List<CreateTrainReads.TrainSchedule> schedules = call("读列车时刻表", () -> trains.trains(level));
        return json(schedules);
    }

    /** 时刻表写成回答：字段名就是客户端读的名字。 */
    static JsonObject json(List<CreateTrainReads.TrainSchedule> schedules) {
        JsonArray list = new JsonArray();
        for (CreateTrainReads.TrainSchedule schedule : schedules) {
            JsonObject train = new JsonObject();
            train.addProperty("id", schedule.id());
            train.addProperty("name", schedule.name());
            train.addProperty("cyclic", schedule.cyclic());
            train.addProperty("current_entry", schedule.currentEntry());
            JsonArray stops = new JsonArray();
            for (CreateTrainReads.Stop stop : schedule.stops()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("entry", stop.entry());
                entry.addProperty("filter", stop.filter());
                entry.addProperty("regex", stop.regex());
                stops.add(entry);
            }
            train.add("stops", stops);
            list.add(train);
        }
        JsonObject out = new JsonObject();
        out.add("trains", list);
        return out;
    }
}
