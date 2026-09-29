// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Map;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.create.CreateStressView;
import org.maiwithu.maicraft.server.machine.create.CreateStressObservation;

/** 手摇曲柄改变原生方块实体的转动，不能只盯方块外观、手持数量和菜单来确认操作。 */
public final class CreateManualInput {
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private CreateManualInput() {}
    public static int durationTicks(JsonObject parameters) {
        if (!parameters.has("duration_seconds")) return 0;
        var value = parameters.get("duration_seconds");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("duration_seconds must be a number between 0 and 30");
        double seconds = value.getAsDouble();
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 30)
            throw new IllegalArgumentException("duration_seconds must be a finite number between 0 and 30");
        return (int) Math.ceil(seconds * 20);
    }
    public static boolean supported(Level world, BlockPos at) {
        return at != null && world.isLoaded(at)
                && BuiltInRegistries.BLOCK.getKey(world.getBlockState(at).getBlock()).toString().equals("create:hand_crank")
                && NativeApi.is(world.getBlockEntity(at), KINETIC);
    }
    public static NativeConfirmation confirmation(Level world, BlockPos at) {
        if (!supported(world, at)) return null;
        var entity = world.getBlockEntity(at); long beforeSync = sync(entity);
        return new NativeConfirmation() {
            public Verdict observe(LocalPlayerContext context) {
                if (context.level() != world || !world.isLoaded(at) || world.getBlockEntity(at) != entity || !supported(world, at)) return Verdict.DIVERGED;
                // 已有同一曲柄的真实自发转动时，持续操作确认维持该状态；第一次仍须等原生转速/网络同步。
                return sync(entity) > beforeSync || active(entity) ? Verdict.APPLIED : Verdict.PENDING;
            }
            public boolean requiresBlockAcknowledgement() { return true; }
        };
    }
    private static long sync(Object entity) { return entity instanceof CreateStressView view ? view.maicraft$selfRotationSync() : 0; }
    private static boolean active(Object entity) {
        try {
            return selfGeneration(((Number) NativeApi.call(entity, KINETIC, "getTheoreticalSpeed")).doubleValue(),
                    ((Number) NativeApi.call(entity, KINETIC, "getGeneratedSpeed")).doubleValue(),
                    NativeApi.truth(NativeApi.call(entity, KINETIC, "hasNetwork")), NativeApi.truth(NativeApi.call(entity, KINETIC, "hasSource")));
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    static boolean selfGeneration(double theoretical, double generated, boolean network, boolean externalSource) {
        // generated 会被客户端 turn() 预测；还要有服务端同步的非零理论转速、自身来源和网络，才算观察到发电。
        return Double.isFinite(theoretical) && Double.isFinite(generated) && theoretical != 0 && generated != 0 && network && !externalSource;
    }
    public static Map<String, Object> evidence(Level world, BlockPos at, int uses) {
        return Map.of("generator_activity_observed", uses > 0, "confirmed_native_uses", uses,
                "currently_generating", supported(world, at) && active(world.getBlockEntity(at)), "machine_production_verified", false);
    }

    /** 保留这次已确认手摇期间的原生网络账，避免模型等工具返回后再查时只看到曲柄已经停转。 */
    public static final class UsageEvidence {
        private JsonObject last;
        private int samples;
        private boolean overstressed, rotated;
        public void observe(Level world, BlockPos at, int confirmedUses) {
            if (confirmedUses < 1 || !supported(world, at)) return;
            var entity = world.getBlockEntity(at);
            if (!active(entity)) return;
            try {
                double rpm = ((Number) NativeApi.call(entity, KINETIC, "getSpeed")).doubleValue();
                if (!Double.isFinite(rpm)) return;
                boolean overload = NativeApi.truth(NativeApi.call(entity, KINETIC, "isOverStressed"));
                var sample = new JsonObject(); sample.addProperty("actual_rpm", rpm); sample.addProperty("overstressed", overload);
                CreateStressObservation.inspect(entity, sample, true, rpm, overload);
                accept(sample, world.getGameTime());
            } catch (RuntimeException | LinkageError unavailable) { /* 可选原生字段不可读时保留已有证据，不把缺失记成应力通过。 */ }
        }
        void accept(JsonObject sample, long tick) {
            samples++; var budget = sample.getAsJsonObject("stress_budget");
            boolean observed = budget != null && "observed".equals(budget.get("status").getAsString());
            // 过载标志可能比应力总账晚同步；两者任一显示超载都保留，后面停机或恢复不能抹掉这次现象。
            overstressed |= sample.get("overstressed").getAsBoolean()
                    || observed && !budget.get("load_within_capacity").getAsBoolean();
            rotated |= sample.get("actual_rpm").getAsDouble() != 0 && !sample.get("overstressed").getAsBoolean();
            if (last == null || observed) { last = sample.deepCopy(); last.addProperty("observed_game_time", tick); }
        }
        public boolean overstressed() { return overstressed; }
        public JsonObject data() {
            var result = new JsonObject(); result.addProperty("native_network_samples", samples);
            result.addProperty("overstress_observed", overstressed); result.addProperty("rotation_observed", rotated);
            result.addProperty("machine_production_verified", false);
            if (last != null) result.add("last_network_snapshot", last.deepCopy());
            else result.addProperty("stress_status", "not_observed_during_confirmed_use");
            return result;
        }
    }
}
