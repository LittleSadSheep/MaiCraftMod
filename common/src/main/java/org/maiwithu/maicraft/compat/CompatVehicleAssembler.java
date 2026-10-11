// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;

/**
 * 联动模组交来的组装办法，登记表给它包的一层：模组停用后答不上（组装能力如实说找不到组装器）；
 * 跑到一半停用或碰到模组接口对不上，按不支持收场，结局照转。
 */
final class CompatVehicleAssembler implements VehicleAssembler {

    private final CompatModule module;
    private final VehicleAssembler assembler;

    CompatVehicleAssembler(CompatModule module, VehicleAssembler assembler) {
        this.module = Objects.requireNonNull(module, "module");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    @Override public Optional<Run> start(Request request) {
        if (!module.active()) return Optional.empty();
        try {
            return assembler.start(request).map(run -> new Guarded(module, run));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 跑起来的一次：推进、暂停、收尾按动作那一层收住；结局只读它自己记下的。 */
    private static final class Guarded extends CompatAction implements Run {
        private final Run run;

        Guarded(CompatModule module, Run run) {
            super(module, run);
            this.run = run;
        }

        @Override public Conversion conversion() {
            return run.conversion();
        }
    }
}
