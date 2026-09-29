// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonObject;

/** 以手摇压机与多机械手的应力账检查观察边界；停机、未知和过载均不能冒充运行验收。 */
public final class CreateStressObservationTest {
    private record Snapshot(float capacity, float load, int size) implements CreateStressView {
        public float maicraft$stressCapacity() { return capacity; }
        public float maicraft$stressLoad() { return load; }
        public int maicraft$stressNetworkSize() { return size; }
    }
    public static void main(String[] args) {
        var noNetwork = inspect(new Snapshot(256, 256, 2), false, 0, false);
        check(noNetwork.getAsJsonObject("stress_budget").get("status").getAsString().equals("no_active_network")
                && !verified(noNetwork), "a stopped standalone machine is not stress-verified merely because it is not overstressed");
        var idle = inspect(new Snapshot(256, 256, 2), true, 0, false);
        check(!verified(idle), "cached capacity with zero RPM cannot pass operating verification");
        var press = inspect(new Snapshot(256, 256, 2), true, 32, false);
        check(verified(press) && press.get("stress_capacity").getAsDouble() == 256
                && press.getAsJsonObject("stress_budget").get("remaining_su").getAsDouble() == 0,
                "the actual single-press network may run exactly at capacity while retaining zero spare stress");
        // 同转速的三个机械手已经需要更多容量；不能只看曲柄存在或等待过载布尔值同步。
        var overload = inspect(new Snapshot(256, 384, 4), true, 32, false);
        check(!verified(overload) && overload.getAsJsonObject("stress_budget").get("remaining_su").getAsDouble() == -128,
                "an observed load above capacity fails even before the overload flag catches up");
        check(!verified(inspect(new Snapshot(256, 128, 2), true, 32, true)), "native overload remains authoritative");
        check(!verified(inspect(new Snapshot(Float.NaN, 0, 2), true, 32, false)), "nonfinite snapshots stay unverified");
        check(!verified(inspect(new Snapshot(256, 128, 0), true, 32, false)), "empty network snapshots stay unverified");
        check(!verified(inspect(new Object(), true, 32, false)), "missing optional observation support stays unknown");
        System.out.println("CreateStressObservationTest: passed");
    }
    private static JsonObject inspect(Object entity, boolean network, double rpm, boolean overloaded) {
        var result = new JsonObject(); CreateStressObservation.inspect(entity, result, network, rpm, overloaded); return result;
    }
    private static boolean verified(JsonObject result) { return result.getAsJsonObject("stress_budget").get("operating_stress_verified").getAsBoolean(); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
