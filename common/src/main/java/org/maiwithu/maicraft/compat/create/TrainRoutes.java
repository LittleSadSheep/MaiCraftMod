// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 坐哪趟车，纯函数：按各趟车时刻表的站序，找从起点附近的车站到终点附近的车站的直达车，没有就找一次换乘。
 * 时刻表循环的车站序前后都行（坐一圈总能到）；不循环的只往后坐。站名对不对得上照 Create 自己的通配规则（换成正则比）。
 */
final class TrainRoutes {

    private TrainRoutes() {}

    /** 一趟车的时刻表：编号、车名、循环不循环、停靠站的正则（按顺序）。 */
    record Schedule(String trainId, String trainName, boolean cyclic, List<Pattern> stops) {
        Schedule {
            stops = List.copyOf(stops);
        }

        /** 这一站在时刻表里第几站；不停为 -1。 */
        int stopOf(String station) {
            for (int index = 0; index < stops.size(); index++) {
                if (stops.get(index).matcher(station).matches()) return index;
            }
            return -1;
        }

        /** 坐这趟车能从 from 坐到 to。 */
        boolean serves(String from, String to) {
            int board = stopOf(from);
            int alight = stopOf(to);
            if (board < 0 || alight < 0 || board == alight) return false;
            return cyclic || board < alight;
        }
    }

    /** 坐一段：哪趟车、在哪站上、在哪站下。 */
    record Hop(String trainId, String trainName, CreateRailways.Station board, CreateRailways.Station alight) {}

    /** 只读操作 create.trains 的回答换成时刻表；正则写坏的那一站当不停。 */
    static List<Schedule> parse(JsonObject answer) {
        List<Schedule> schedules = new ArrayList<>();
        JsonArray trains = answer.has("trains") && answer.get("trains").isJsonArray() ? answer.getAsJsonArray("trains") : new JsonArray();
        for (JsonElement element : trains) {
            if (!element.isJsonObject()) continue;
            JsonObject train = element.getAsJsonObject();
            List<Pattern> stops = new ArrayList<>();
            JsonArray entries = train.has("stops") ? train.getAsJsonArray("stops") : new JsonArray();
            for (JsonElement stop : entries) {
                try {
                    stops.add(Pattern.compile(stop.getAsJsonObject().get("regex").getAsString()));
                } catch (PatternSyntaxException | IllegalStateException | NullPointerException broken) {
                    stops.add(Pattern.compile("(?!)"));
                }
            }
            schedules.add(new Schedule(text(train, "id"), text(train, "name"),
                    train.has("cyclic") && train.get("cyclic").getAsBoolean(), stops));
        }
        return schedules;
    }

    /**
     * 从起点附近的车站坐到终点附近的车站：先找直达，没有再找一次换乘；起点、终点车站按离得近排好再交进来。
     *
     * @param starts   起点附近走得到的车站（近的在前）
     * @param ends     终点附近的车站（近的在前）
     * @param stations 全部车站（换乘站从里面挑）
     */
    static Optional<List<Hop>> plan(List<CreateRailways.Station> starts, List<CreateRailways.Station> ends,
            List<CreateRailways.Station> stations, List<Schedule> schedules) {
        for (CreateRailways.Station start : starts) {
            for (CreateRailways.Station end : ends) {
                if (start.name().equals(end.name())) continue;
                for (Schedule schedule : schedules) {
                    if (schedule.serves(start.name(), end.name())) {
                        return Optional.of(List.of(new Hop(schedule.trainId(), schedule.trainName(), start, end)));
                    }
                }
            }
        }
        for (CreateRailways.Station start : starts) {
            for (CreateRailways.Station end : ends) {
                for (Schedule first : schedules) {
                    for (Schedule second : schedules) {
                        if (first.trainId().equals(second.trainId())) continue;
                        Optional<CreateRailways.Station> transfer = stations.stream()
                                .filter(station -> !station.name().equals(start.name()) && !station.name().equals(end.name()))
                                .filter(station -> first.serves(start.name(), station.name())
                                        && second.serves(station.name(), end.name()))
                                .min(Comparator.comparingDouble(station -> station.pos().distSqr(end.pos())));
                        if (transfer.isPresent()) {
                            return Optional.of(List.of(new Hop(first.trainId(), first.trainName(), start, transfer.get()),
                                    new Hop(second.trainId(), second.trainName(), transfer.get(), end)));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
