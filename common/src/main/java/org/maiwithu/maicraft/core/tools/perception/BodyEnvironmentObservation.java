package org.maiwithu.maicraft.core.tools.perception;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;

/** 同一次观察保留身体位置、落地与浸水事实，附近发现水面不能代替角色自身的原生状态。 */
public final class BodyEnvironmentObservation {
    private BodyEnvironmentObservation() {}

    public static JsonObject describe(LocalPlayer player) {
        // 先固定观察时刻和位置，再逐项读取身体状态；浅水中站稳和完全离水必须能分别表达。
        JsonObject result = new JsonObject();
        result.addProperty("game_time", player.level().getGameTime());
        result.addProperty("dimension", player.level().dimension().location().toString());
        JsonObject position = new JsonObject();
        position.addProperty("x", player.getX());
        position.addProperty("y", player.getY());
        position.addProperty("z", player.getZ());
        result.add("position", position);
        result.addProperty("on_ground", player.onGround());
        result.addProperty("in_water", player.isInWater());
        result.addProperty("underwater", player.isEyeInFluid(FluidTags.WATER));
        result.addProperty("swimming", player.isSwimming());
        result.addProperty("sprinting", player.isSprinting());
        return result;
    }
}
