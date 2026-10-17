// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.TrainScheduler;

/**
 * 联动模组交来的定时刻表办法，登记表给它包的一层：模组停用后答不上（machine_configure 如实说定不了）；
 * 跑到一半停用或碰到模组接口对不上，按不支持收场，经过照转。
 */
final class CompatTrainScheduler implements TrainScheduler {

    private final CompatModule module;
    private final TrainScheduler scheduler;

    CompatTrainScheduler(CompatModule module, TrainScheduler scheduler) {
        this.module = Objects.requireNonNull(module, "module");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override public Optional<Run> schedule(Request request) {
        if (!module.active()) return Optional.empty();
        try {
            return scheduler.schedule(request).map(run -> new Guarded(module, run));
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
