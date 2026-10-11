// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 机器能力看载具、改操纵表要的三样：联动登记的读载具办法、操纵表，和把观察编号 s# 换成结构身份的查法。
 * 载具也是机器：machine_inspect 指 s# 时给出 vehicle 一节与操纵表，machine_configure 指 s# 时改操纵表。
 *
 * @param readers     读载具的办法，按联动清单顺序；第一个读得了的来读
 * @param controls    操纵表（试过的、LLM 定的按结构身份存着）
 * @param structureOf 观察编号 s# 换成结构身份；不是结构或已经不在为空
 */
public record MachineVehicles(List<VehicleReader> readers, VehicleControls controls,
                              Function<String, Optional<String>> structureOf) {

    public MachineVehicles {
        readers = List.copyOf(readers);
        Objects.requireNonNull(controls, "controls");
        Objects.requireNonNull(structureOf, "structureOf");
    }

    /** 读这个结构：第一个读得了的来读。 */
    Optional<VehicleReader.View> read(String structureId) {
        return readers.stream().map(reader -> reader.read(structureId)).flatMap(Optional::stream).findFirst();
    }
}
