// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 联动模组交来的驾驶办法，登记表给它包的一层：模组停用后答不上（驾驶能力如实说没有认得的控制件）；
 * 跑到一半停用或碰到模组接口对不上，按不支持收场，经过照转。
 */
final class CompatVehicleDriver implements VehicleDriver {

    private final CompatModule module;
    private final VehicleDriver driver;

    CompatVehicleDriver(CompatModule module, VehicleDriver driver) {
        this.module = Objects.requireNonNull(module, "module");
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    @Override public Optional<WorldPosition> boardAt(String structureId) {
        if (!module.active()) return Optional.empty();
        try {
            return driver.boardAt(structureId);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public Optional<Run> test(TestRequest request) {
        if (!module.active()) return Optional.empty();
        try {
            return driver.test(request).map(run -> new Guarded(module, run));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 跑起来的一次：推进、暂停、收尾按动作那一层收住；经过只读它自己记下的。 */
    private static final class Guarded extends CompatAction implements Run {
        private final Run run;

        Guarded(CompatModule module, Run run) {
            super(module, run);
            this.run = run;
        }

        @Override public Report report() {
            return run.report();
        }
    }
}
