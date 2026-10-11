// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 机器能力看载具要的两样：联动登记的读载具办法，和把观察编号 s# 换成结构身份的查法。
 * 载具也是机器：machine_inspect 指 s# 时用它们给出 vehicle 一节。
 *
 * @param readers     读载具的办法，按联动清单顺序；第一个读得了的来读
 * @param structureOf 观察编号 s# 换成结构身份；不是结构或已经不在为空
 */
public record MachineVehicles(List<VehicleReader> readers, Function<String, Optional<String>> structureOf) {

    public MachineVehicles {
        readers = List.copyOf(readers);
        Objects.requireNonNull(structureOf, "structureOf");
    }

    /** 没有任何读载具办法的一份：离线测试与没装联动时用。 */
    public static MachineVehicles none() {
        return new MachineVehicles(List.of(), id -> Optional.empty());
    }
}
