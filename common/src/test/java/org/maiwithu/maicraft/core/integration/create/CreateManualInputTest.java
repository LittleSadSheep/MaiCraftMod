// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.maiwithu.maicraft.server.machine.mixin.CreateStressObservationMixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        System.out.println("CreateManualInputTest: passed");
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
