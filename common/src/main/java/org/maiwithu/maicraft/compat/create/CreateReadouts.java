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

    // 一格流体：流体 ID、多少毫桶，读得到容量时写成"已有/能装"。
    static String fluid(CreateStations.FluidReading reading) {
        return reading.fluidId() + " " + reading.amount() + (reading.capacity() > 0 ? "/" + reading.capacity() : "") + " mB";
    }
}
