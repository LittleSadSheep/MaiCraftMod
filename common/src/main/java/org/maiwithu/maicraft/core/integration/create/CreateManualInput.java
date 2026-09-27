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
}
