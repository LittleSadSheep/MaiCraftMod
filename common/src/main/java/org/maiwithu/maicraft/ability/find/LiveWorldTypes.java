// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/**
 * 游戏类型目录的生产读端：直接问原版注册表（含模组注册）。
 * 只读，不进世界也能答；不写任何静态状态。
 */
final class LiveWorldTypes implements ReadsWorldTypes {

    @Override
    public boolean blockTypeExists(String blockIdOrTag) {
        String spec = blockIdOrTag.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            if (tagId == null) return false;
            // 空标签要不了东西：一件注册方块都不挂的标签算没有。
            return BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, tagId))
                    .map(set -> {
                        int[] count = {0};
                        set.forEach(holder -> count[0]++);
                        return count[0] > 0;
                    }).orElse(false);
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id);
    }

    @Override
    public boolean entityTypeExists(String entityTypeId) {
        return entityTypeOf(entityTypeId).isPresent();
    }

    /** 方块 ID 或标签展开成方块集合；写法不合法或都没注册给空集。包内复用，避免两份解析。 */
    static Set<Block> blocksOf(List<String> selectors) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String selector : selectors) {
            String spec = selector.toLowerCase(Locale.ROOT);
            if (spec.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
                if (tagId == null) continue;
                BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, tagId))
                        .ifPresent(set -> set.forEach(holder -> blocks.add(holder.value())));
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(spec);
            if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        }
        return blocks;
    }

    /** 实体类型 ID 换成类型对象；写法不合法或没注册给空。 */
    static Optional<EntityType<?>> entityTypeOf(String entityTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(entityTypeId.toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
    }
}
