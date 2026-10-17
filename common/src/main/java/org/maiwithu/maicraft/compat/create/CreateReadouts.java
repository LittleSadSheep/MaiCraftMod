// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 机械动力方块看得见的附加读数并进机器状态，纯函数：装着什么流体、显示面上的字、无线红石的频率与收发、
 * 装置在不在动。读不到的那一样不写，不猜。
 */
final class CreateReadouts {

    private CreateReadouts() {
    }

    /** 把附加读数并进已有的机器状态。 */
    static MachineState with(MachineState state, List<CreateStations.FluidReading> fluids, Optional<String> text,
            Optional<CreateStations.LinkReading> link, Optional<Boolean> moving) {
        Map<String, Object> readings = new LinkedHashMap<>(state.readings());
        if (!fluids.isEmpty()) {
            readings.put("fluids", fluids.stream().map(CreateReadouts::fluid).toList());
        }
        text.ifPresent(shown -> readings.put("text", shown));
        link.ifPresent(reading -> {
            readings.put("frequency", reading.frequencies().stream().map(LinkSettings::itemValue).toList());
            readings.put("link_mode", reading.receiver() ? LinkSettings.RECEIVE : LinkSettings.TRANSMIT);
            readings.put("powered", reading.powered());
        });
        moving.ifPresent(running -> readings.put("moving", running));
        return new MachineState(state.activity(), state.speed(), state.progress(), state.shown(), readings, state.note());
    }

    /**
     * 蓝图加农炮与蓝图桌的读数并进机器状态：加农炮开着算在干活，状态原名、放了几格、卡在缺什么、剩几发火药；
     * 蓝图桌正在传时写传了多少。
     */
    static MachineState schematic(MachineState state, Optional<CreateSchematics.CannonReading> cannon,
            Optional<Float> uploading) {
        Map<String, Object> readings = new LinkedHashMap<>(state.readings());
        MachineState.Activity activity = state.activity();
        Double progress = state.progress();
        if (cannon.isPresent()) {
            CreateSchematics.CannonReading reading = cannon.get();
            activity = reading.running() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
            readings.put("status", reading.status());
            readings.put("placed", reading.placed() + "/" + reading.toPlace());
            reading.missing().ifPresent(item -> readings.put("missing", item));
            readings.put("shots_left", reading.fuel());
            if (reading.toPlace() > 0) progress = (double) reading.placed() / reading.toPlace();
        }
        if (uploading.isPresent()) {
            activity = MachineState.Activity.RUNNING;
            progress = (double) uploading.get();
        }
        return new MachineState(activity, state.speed(), progress, state.shown(), readings, state.note());
    }

    // 一格流体：流体 ID、多少毫桶，读得到容量时写成"已有/能装"。
    static String fluid(CreateStations.FluidReading reading) {
        return reading.fluidId() + " " + reading.amount() + (reading.capacity() > 0 ? "/" + reading.capacity() : "") + " mB";
    }
}
