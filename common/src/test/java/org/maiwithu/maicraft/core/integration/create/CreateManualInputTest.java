// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.maiwithu.maicraft.server.machine.mixin.CreateStressObservationMixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.maiwithu.maicraft.server.machine.create.CreateStressObservation;
import org.maiwithu.maicraft.server.machine.create.CreateStressView;

/** 客户端转柄动画不等于真实发电；短暂收到的原生转动同步也不能因回执稍晚而丢失。 */
public final class CreateManualInputTest {
    public static void main(String[] args) throws Exception {
        check(!CreateManualInput.selfGeneration(0, 32, false, false), "predicted inUse without native network rotation is not proof");
        check(!CreateManualInput.selfGeneration(32, 32, true, true), "external driving is not self generation by the selected crank");
        check(CreateManualInput.selfGeneration(-32, -32, true, false), "a synchronized self-driven generator is observable in either direction");
        check(!CreateManualInput.selfGeneration(Double.NaN, 32, true, false), "invalid native speed remains unknown");
        check(duration("{}") == 0 && duration("{duration_seconds:1.25}") == 25 && duration("{duration_seconds:30}") == 600,
                "finite semantic duration preserves fractional seconds and the upper bound");
        for (String bad : new String[]{"{duration_seconds:-1}", "{duration_seconds:31}", "{duration_seconds:'2'}", "{duration_seconds:true}"}) {
            try { duration(bad); throw new AssertionError("invalid duration accepted: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        var observed = new Observed(); observed.speed = 32;
        read(observed, false); check(observed.maicraft$selfRotationSync() == 0, "local/load reads do not become server feedback");
        observed.external = true; read(observed, true);
        check(observed.maicraft$selfRotationSync() == 0, "external power packets do not masquerade as crank generation");
        observed.external = false; read(observed, true);
        observed.speed = 0; read(observed, true);
        check(observed.maicraft$selfRotationSync() == 1, "the short native pulse remains observable after the stop packet");
        retainedStressEvidence();
        System.out.println("CreateManualInputTest: passed");
    }
    private static void retainedStressEvidence() {
        var usage = new CreateManualInput.UsageEvidence();
        check(usage.data().get("native_network_samples").getAsInt() == 0 && usage.data().has("stress_status"),
                "no native observation is explicitly unknown rather than a passed stress check");
        // 原生快照输入覆盖三机械手超载及后来恢复；只测试历史证据保留，不冒充夹具真的摇动了世界中的曲柄。
        var overload = sample(256, 384, 0, false); usage.accept(overload, 100);
        check(usage.overstressed() && !usage.data().get("rotation_observed").getAsBoolean(),
                "load above capacity is retained before the native overstress flag catches up");
        usage.accept(sample(512, 384, 32, false), 101);
        var data = usage.data();
        check(data.get("overstress_observed").getAsBoolean() && data.get("rotation_observed").getAsBoolean()
                && data.getAsJsonObject("last_network_snapshot").get("observed_game_time").getAsLong() == 101
                && !data.get("machine_production_verified").getAsBoolean(),
                "later rotation cannot erase an earlier overload or certify machine output");
        data.getAsJsonObject("last_network_snapshot").addProperty("actual_rpm", 0);
        check(usage.data().getAsJsonObject("last_network_snapshot").get("actual_rpm").getAsInt() == 32,
                "returned data cannot mutate the retained observation");
    }
    private static JsonObject sample(float capacity, float load, double rpm, boolean overloaded) {
        var sample = new JsonObject(); sample.addProperty("actual_rpm", rpm); sample.addProperty("overstressed", overloaded);
        var view = new CreateStressView() {
            public float maicraft$stressCapacity() { return capacity; }
            public float maicraft$stressLoad() { return load; }
            public int maicraft$stressNetworkSize() { return 4; }
        };
        CreateStressObservation.inspect(view, sample, true, rpm, overloaded); return sample;
    }
    private static int duration(String json) { return CreateManualInput.durationTicks(JsonParser.parseString(json).getAsJsonObject()); }
    private static void read(Observed entity, boolean clientPacket) throws Exception {
        var method = CreateStressObservationMixin.class.getDeclaredMethod("maicraft$observeRotation", CompoundTag.class, HolderLookup.Provider.class, boolean.class, CallbackInfo.class);
        method.setAccessible(true); method.invoke(entity, new CompoundTag(), null, clientPacket, new CallbackInfo("read", false));
    }
    private static final class Observed extends CreateStressObservationMixin {
        float speed; boolean external;
        public float getTheoreticalSpeed() { return speed; }
        public boolean hasSource() { return external; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
