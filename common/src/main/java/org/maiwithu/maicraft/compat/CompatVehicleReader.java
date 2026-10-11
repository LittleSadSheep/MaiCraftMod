// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 联动模组交来的读载具办法，登记表给它包的一层：模组停用后答不上，碰到模组接口对不上也按答不上，
 * machine_inspect 照实说读不了这辆。
 */
final class CompatVehicleReader implements VehicleReader {

    private final CompatModule module;
    private final VehicleReader reader;

    CompatVehicleReader(CompatModule module, VehicleReader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override public Optional<View> read(String structureId) {
        if (!module.active()) return Optional.empty();
        try {
            return reader.read(structureId);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }
}
