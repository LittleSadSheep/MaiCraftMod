package org.maiwithu.maicraft.core.task.collect;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 先解释模型选中的类型或物品堆，再提交普通靠近拾取；拼错的选择不能扩大成全部拾取。 */
public record CollectItemsRequest(Set<Item> filter, int radius, ResourceLocation dimension, UUID target) {
    public CollectItemsRequest {
        filter = Set.copyOf(filter);
    }

    public static CollectItemsRequest parse(JsonObject args) {
        var filter = new LinkedHashSet<Item>();
        if (args.has("item_ids")) {
            var values = args.get("item_ids");
            if (!values.isJsonArray() || values.getAsJsonArray().isEmpty())
                throw new IllegalArgumentException("item_ids must be a non-empty array of registered item IDs; omit it to collect all");
            for (var value : values.getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("each item_ids entry must be a registered item ID");
                filter.add(ToolArgs.parseItem(value.getAsString()));
            }
        }
        int radius = 16;
        if (args.has("radius")) {
            var value = args.get("radius");
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                radius = value.getAsBigDecimal().intValueExact();
                if (radius < 1 || radius > 48) throw new ArithmeticException();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException("radius must be an integer from 1 to 48");
            }
        }
        ResourceLocation dimension = null;
        UUID target = null;
        if (args.has("drop_ref")) {
            // 观察引用同时绑定维度和 UUID；数字实体号重用或角色换维度时不能捡成另一堆。
            var value = args.get("drop_ref");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("drop_ref must be copied from nearby_entities");
            String ref = value.getAsString();
            int split = ref.lastIndexOf('|');
            try {
                if (split < 1) throw new IllegalArgumentException();
                dimension = ResourceLocation.tryParse(ref.substring(0, split));
                if (dimension == null) throw new IllegalArgumentException();
                target = UUID.fromString(ref.substring(split + 1));
                if (!ref.equals(dimension + "|" + target)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid drop_ref; copy the complete reference from nearby_entities");
            }
        }
        return new CollectItemsRequest(filter, radius, dimension, target);
    }

    public CollectItemsTaskRecord task(LocalPlayer player, ToolContext context) {
        if (dimension != null && !dimension.equals(player.level().dimension().location()))
            throw new IllegalArgumentException("drop_ref belongs to another dimension; observe nearby_entities in the current world");
        String label = target != null ? "selected drop" : filter.isEmpty() ? "all items" : "selected item types";
        // 所选引用交给执行器逐刻核对；目标已消失时必须报告未确认拾取，不能按空范围成功收尾。
        return new CollectItemsTaskRecord(context.toolCallId(), context.deadline(60 * 20),
                filter, radius, label, target == null ? Set.of() : Set.of(target), dimension);
    }
}
