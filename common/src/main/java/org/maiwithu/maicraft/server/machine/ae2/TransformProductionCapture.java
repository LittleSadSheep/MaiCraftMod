// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 观察 AE2 已经完成的真实转化；不改原料、配方选择或实体生成结果，也不接收客户端伪造的生产回执。 */
public final class TransformProductionCapture {
    private TransformProductionCapture() {}

    public static boolean available() {
        if (!NativeApi.present("net.neoforged.neoforge.common.NeoForge")
                || !NativeApi.present("appeng.recipes.transform.TransformLogic")) return false;
        try {
            // Class.forName(false) 已等待目标类与晚期注入全部定义完成；从 Mixin 创建插件时使用的同一 ClassProvider 读取最终树。
            var provider = org.spongepowered.asm.service.MixinService.getService().getClassProvider();
            Class<?> plugin = provider.findClass("org.maiwithu.maicraft.network.OptionalServerMixinPlugin", false);
            boolean installed = Boolean.TRUE.equals(plugin.getMethod("worldTransformEvents").invoke(null));
            Constants.LOG.info("[world-transform] native event hook verified after class definition: {} (plugin loader: {})",
                    installed, plugin.getClassLoader());
            return installed;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // 无法证明最终调用链时保持客户端观察模式；不能把其他机器的事件能力当作本次转化有强回执。
            Constants.LOG.warn("[world-transform] native event hook capability unavailable ({})", unavailable.getClass().getSimpleName());
            return false;
        }
    }

    public static void spawned(Level nativeLevel, Entity emitted, boolean accepted, BlockPos at,
                               RecipeHolder<?> recipe, List<ItemStack> consumed,
                               Reference2IntMap<ItemEntity> claimed) {
        if (!(nativeLevel instanceof ServerLevel level) || !(emitted instanceof ItemEntity output)
                || !level.getServer().isSameThread()) return;
        try {
            // addFreshEntity 可能被事件取消；AE2 自己仍返回 true，因此必须检查真正入世且尚未移除的产物。
            boolean present = !output.isRemoved() && level.getEntity(output.getUUID()) == output;
            if (!accepted || !present || claimed.isEmpty() || claimed.size() > 64) return;
            JsonArray entities = new JsonArray(); long claimedCount = 0;
            for (var entry : claimed.reference2IntEntrySet()) {
                if (entry.getIntValue() <= 0) return;
                JsonObject value = new JsonObject(); value.addProperty("entity_uuid", entry.getKey().getUUID().toString());
                value.addProperty("amount", entry.getIntValue()); entities.add(value); claimedCount += entry.getIntValue();
            }
            if (claimedCount != consumed.stream().mapToLong(ItemStack::getCount).sum()) return;
            // 使用生成调用前冻结的流体格，避免生成事件监听器移动触发物后把生产错记到另一个池子。
            emit(accepted, present, consumed, output.getItem(), level.registryAccess(), (inputs, outputs) ->
                    ServerProductionEvents.recordProduction(level, at, recipe.id().toString(), inputs, outputs, 1,
                            "ae2.transform.tryTransform", entities, output.getUUID()));
        } catch (RuntimeException | LinkageError unavailable) {
            // 观察失败不能回滚或重复原版已经发生的转化；调用方拿不到原生事件时必须保留证据不足状态。
            Constants.LOG.warn("Could not capture native world transformation evidence ({})", unavailable.getClass().getSimpleName());
        }
    }

    static void emit(boolean accepted, boolean present, List<ItemStack> consumed, ItemStack output,
                     HolderLookup.Provider registries, BiConsumer<JsonArray, JsonArray> sink) {
        if (!accepted || !present || output.isEmpty() || consumed.isEmpty() || consumed.size() > 64) return;
        JsonArray inputs = new JsonArray(), outputs = new JsonArray();
        // 记录 split 已实际扣下的每一叠，保留组件与数量；多件输入不能被“配方运行一次”这个计数再次乘算。
        for (ItemStack stack : consumed) {
            if (stack.isEmpty()) return;
            inputs.add(resource(stack, registries));
        }
        outputs.add(resource(output, registries)); sink.accept(inputs, outputs);
    }

    private static JsonObject resource(ItemStack stack, HolderLookup.Provider registries) {
        JsonObject identity = ResourceIdentity.item(stack, registries), value = new JsonObject();
        value.addProperty("resource_id", ResourceIdentity.key(identity)); value.add("identity", identity);
        value.addProperty("amount", stack.getCount()); return value;
    }
}
