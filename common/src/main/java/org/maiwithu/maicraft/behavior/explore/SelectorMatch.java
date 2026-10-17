// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * 留意清单与目标选择器的匹配：一个注册 ID 等于某个选择器，或挂在某个 # 标签下。
 * 方块查静态注册表；群系在数据包注册表里，读端从世界拿注册表后调这里。
 */
public final class SelectorMatch {

    private SelectorMatch() {}

    /** 方块注册 ID 命中任一选择器（整 ID 相等，或方块在标签下）。 */
    public static boolean blockMatches(String blockId, List<String> selectors) {
        return matches(BuiltInRegistries.BLOCK, registeredIdOf(blockId), selectors);
    }

    /** 群系注册 ID 命中任一选择器；群系注册表由读端从世界拿。 */
    public static boolean biomeMatches(Registry<Biome> biomes, String biomeId, List<String> selectors) {
        return matches(biomes, registeredIdOf(biomeId), selectors);
    }

    private static String registeredIdOf(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    private static <T> boolean matches(Registry<T> registry, String registeredId, List<String> selectors) {
        if (registeredId == null) {
            return false;
        }
        ResourceLocation location = ResourceLocation.tryParse(registeredId);
        if (location == null) {
            return false;
        }
        T value = registry.get(location);
        if (value == null) {
            return false;
        }
        for (String selector : selectors) {
            String spec = selector.toLowerCase(Locale.ROOT);
            if (spec.equals(registeredId)) {
                return true;
            }
            if (spec.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
                if (tagId != null && inTag(registry, TagKey.create(registry.key(), tagId), value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 值在不在标签下：按注册表的标签读。 */
    private static <T> boolean inTag(Registry<T> registry, TagKey<T> tag, T value) {
        return registry.getTag(tag)
                .map(holders -> holders.contains(registry.wrapAsHolder(value)))
                .orElse(false);
    }
}
