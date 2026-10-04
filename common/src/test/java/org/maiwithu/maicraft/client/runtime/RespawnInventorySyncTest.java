// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.intent.IntentRuntime;

/**
 * 重生回执的库存对比等内容包同步后再算：绑定瞬间读取会把 keepInventory 场景
 * 误报成"全部丢失"并引导不必要的资产对账。内容包未到时事件延后，窗口到期则按
 * 当时读数如实发布（真掉落场景本就是空包）。
 */
public final class RespawnInventorySyncTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            GameplayAttentionMonitor.reset();
            armDeath(h);
            GameplayAttentionMonitor.afterSemanticBind(h.player);
            for (int i = 0; i < 5; i++) { resolve(h); h.nextTick(); }
            check(respawnEvents().isEmpty(), "内容包未到且窗口未到期时不得提前发布库存对比");
            for (int i = 0; i < 35; i++) { resolve(h); h.nextTick(); }
            var delayed = respawnEvents();
            check(delayed.size() == 1, "窗口到期后应按当时读数发布一次重生回执");
            check(delayed.get(0).getAsJsonObject().getAsJsonObject("data")
                    .get("inventory_missing_count").getAsInt() == 64,
                    "真掉落场景（重生空包）应如实报告缺失数量");

            // 场景 B：内容包先到（keepInventory，物品已在背包）→ 事件立即发布且缺失为零。
            int before = respawnEvents().size();
            GameplayAttentionMonitor.reset();
            armDeath(h);
            h.player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            GameplayAttentionMonitor.afterSemanticBind(h.player);
            resolve(h);
            var immediate = respawnEvents();
            check(immediate.size() == before + 1, "内容包先到时应立即发布重生回执");
            check(immediate.get(immediate.size() - 1).getAsJsonObject().getAsJsonObject("data")
                    .get("inventory_missing_count").getAsInt() == 0,
                    "keepInventory 场景不得误报库存缺失");
        }
        System.out.println("RespawnInventorySyncTest: passed");
    }

    /** 反射调用发布解析入口；不引入天气与昼夜链路的夹具依赖。 */
    private static void resolve(InteractionWorldTestHarness h) throws Exception {
        Method resolve = GameplayAttentionMonitor.class.getDeclaredMethod("resolveRespawnReport",
                net.minecraft.client.player.LocalPlayer.class);
        resolve.setAccessible(true);
        resolve.invoke(null, h.player);
    }

    /** 反射布置死亡快照与重生观察态；死亡背包记 64 块圆石。 */
    private static void armDeath(InteractionWorldTestHarness h) throws Exception {
        Class<?> snapshot = Class.forName(GameplayAttentionMonitor.class.getName() + "$DeathSnapshot");
        Constructor<?> ctor = snapshot.getDeclaredConstructor(UUID.class, String.class,
                net.minecraft.world.phys.Vec3.class, long.class, Map.class, int.class, boolean.class);
        ctor.setAccessible(true);
        Object death = ctor.newInstance(null, "minecraft:overworld",
                net.minecraft.world.phys.Vec3.ZERO, 0L, Map.of("minecraft:cobblestone", 64), 64, false);
        field("lastDeath").set(null, death);
        field("lifeState").set(null, Enum.valueOf(
                (Class<? extends Enum>) Class.forName(GameplayAttentionMonitor.class.getName() + "$LifeState"),
                "RESPAWN_OBSERVED"));
    }

    private static JsonArray respawnEvents() {
        var events = IntentRuntime.get().attention(0, 256).getAsJsonArray("events");
        var matches = new JsonArray();
        for (var element : events) {
            JsonObject event = element.getAsJsonObject();
            if ("agent.respawned".equals(event.get("type").getAsString())) matches.add(event);
        }
        return matches;
    }

    private static Field field(String name) throws Exception {
        return field(GameplayAttentionMonitor.class, name);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
