package org.maiwithu.maicraft.core.scan;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import org.maiwithu.maicraft.core.inventory.InventoryComponentFacts;

import java.util.UUID;

/** 拾取前展示每堆真实物品及所在位置；引用绑定维度与 UUID，物品漂移时仍指向原来那堆。 */
public final class DroppedItemObservation {
    private DroppedItemObservation() {}

    public static String reference(LocalPlayer player, UUID uuid) {
        return player.level().dimension().location() + "|" + uuid;
    }

    public static JsonObject describe(LocalPlayer player, ItemEntity drop) {
        var stack = drop.getItem();
        var result = new JsonObject();
        result.addProperty("drop_ref", reference(player, drop.getUUID()));
        result.addProperty("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("name", stack.getHoverName().getString());
        result.addProperty("dimension", player.level().dimension().location().toString());
        refresh(result, player, drop);
        // 同种工具的名称、附魔和损伤也会影响选择；复用背包组件事实，不能只按注册物品名合并不同堆。
        InventoryComponentFacts.observe(stack, player.registryAccess()).entrySet()
                .forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result;
    }

    /** 角色靠近时更新漂移位置、剩余数量和观察时刻；已核对未变的组件不用每刻重新序列化。 */
    public static void refresh(JsonObject result, LocalPlayer player, ItemEntity drop) {
        result.addProperty("count", drop.getItem().getCount());
        result.addProperty("observed_game_time", player.level().getGameTime());
        var position = new JsonObject();
        position.addProperty("x", drop.getX());
        position.addProperty("y", drop.getY());
        position.addProperty("z", drop.getZ());
        result.add("position", position);
        result.addProperty("distance", player.distanceTo(drop));
        result.addProperty("pickup_delay", drop.hasPickUpDelay());
    }
}
