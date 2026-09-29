// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonObject;

/** 区分停机、超载和已经运行的应力账；未转动时 isOverStressed=false 不能作为动力验收。 */
public final class CreateStressObservation {
    private CreateStressObservation() {}
    public static void inspect(Object entity, JsonObject result, boolean hasNetwork, double rpm, boolean overloaded) {
        var budget = new JsonObject(); result.add("stress_budget", budget);
        budget.addProperty("scope", "current_native_network; planned_additions_and_machine_production_unverified");
        budget.addProperty("operating_stress_verified", false);
        result.addProperty("stress_capacity", "unknown");
        if (!hasNetwork) { budget.addProperty("status", "no_active_network"); return; }
        if (!(entity instanceof CreateStressView view)) { budget.addProperty("status", "native_stress_observation_unavailable"); return; }
        double capacity = view.maicraft$stressCapacity(), load = view.maicraft$stressLoad();
        int size = view.maicraft$stressNetworkSize();
        if (!Double.isFinite(capacity) || !Double.isFinite(load) || capacity < 0 || load < 0 || size < 1) {
            budget.addProperty("status", "invalid_native_stress_snapshot"); return;
        }
        // 使用原生总容量与总负载，减速、倍速和同网其他机器均已经体现在这份账里，不能只数当前蓝图的方块。
        budget.addProperty("status", "observed"); budget.addProperty("capacity_su", capacity);
        budget.addProperty("load_su", load); budget.addProperty("remaining_su", capacity - load);
        budget.addProperty("network_size", size); budget.addProperty("load_within_capacity", load <= capacity);
        budget.addProperty("operating_stress_verified", Double.isFinite(rpm) && rpm != 0 && capacity > 0 && !overloaded && load <= capacity);
        result.addProperty("stress_capacity", capacity);
    }
}
