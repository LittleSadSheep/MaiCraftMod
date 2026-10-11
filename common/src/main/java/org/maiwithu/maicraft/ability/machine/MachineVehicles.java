// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.TrainScheduler;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 机器能力看载具、改操纵表、给列车定时刻表要的几样：联动登记的读载具办法与定时刻表办法、操纵表，
 * 和把观察编号 s#、e# 换成结构身份、实体编号的查法。载具也是机器：machine_inspect 指 s# 时给出 vehicle 一节与操纵表，
 * machine_configure 指 s# 时改操纵表、指列车车厢 e# 时定时刻表。
 *
 * @param readers     读载具的办法，按联动清单顺序；第一个读得了的来读
 * @param controls    操纵表（试过的、LLM 定的按结构身份存着）
 * @param structureOf 观察编号 s# 换成结构身份；不是结构或已经不在为空
 * @param schedulers  给列车定时刻表的办法（machine_configure 指列车车厢 e# 时用）
 * @param entityOf    观察编号 e# 换成游戏里的实体编号；不是实体或已经不在为空
 */
public record MachineVehicles(List<VehicleReader> readers, VehicleControls controls,
                              Function<String, Optional<String>> structureOf, List<TrainScheduler> schedulers,
                              Function<String, Optional<Integer>> entityOf) {

    public MachineVehicles {
        readers = List.copyOf(readers);
        Objects.requireNonNull(controls, "controls");
        Objects.requireNonNull(structureOf, "structureOf");
        schedulers = List.copyOf(schedulers);
        Objects.requireNonNull(entityOf, "entityOf");
    }

    /** 读这个结构：第一个读得了的来读。 */
    Optional<VehicleReader.View> read(String structureId) {
        return readers.stream().map(reader -> reader.read(structureId)).flatMap(Optional::stream).findFirst();
    }
}
