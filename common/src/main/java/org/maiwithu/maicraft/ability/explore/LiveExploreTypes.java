// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.explore;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.maiwithu.maicraft.behavior.explore.StructureClues;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 游戏类型目录的生产读端：方块与实体问原版注册表，群系与结构是数据包注册表，
 * 从当刻世界拿。只读；不在世界里答不了群系与结构，写岔的写法按不存在处理。
 */
final class LiveExploreTypes implements ReadsExploreTypes {

    private final Supplier<PlayerContext> context;

    LiveExploreTypes(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public boolean biomeExists(String selector) {
        String spec = selector.toLowerCase(Locale.ROOT);
        Optional<Registry<Biome>> registry = worldRegistry(Registries.BIOME);
        if (registry.isEmpty()) {
            return false;
        }
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            return tagId != null && tagHasEntries(registry.get(),
                    TagKey.create(Registries.BIOME, tagId));
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        return id != null && registry.get().containsKey(id);
    }

    @Override
    public boolean terrainExists(String selector) {
        return selector.equalsIgnoreCase("lava_pool");
    }

    @Override
    public boolean blockExists(String selector) {
        String spec = selector.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            if (tagId == null) {
                return false;
            }
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
    public boolean entityTypeExists(String selector) {
        ResourceLocation id = ResourceLocation.tryParse(selector.toLowerCase(Locale.ROOT));
        return id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id);
    }

    @Override
    public boolean structureExists(String selector) {
        ResourceLocation id = ResourceLocation.tryParse(selector.toLowerCase(Locale.ROOT));
        if (id == null) return false;
        // 结构注册表只在服务端有，客户端读不到：读不到就按有没有线索档案认（原版常见结构都有）。
        // 原先硬取注册表抛"Missing registry"，实机 10-14 在下界带着要塞留意清单跑图，一开就程序出错。
        return worldRegistry(Registries.STRUCTURE).map(registry -> registry.containsKey(id))
                .orElseGet(() -> StructureClues.known(id.toString()));
    }

    private <T> boolean tagHasEntries(Registry<T> registry, TagKey<T> tag) {
        Set<T> entries = new LinkedHashSet<>();
        registry.getTag(tag).ifPresent(holders -> holders.forEach(holder -> entries.add(holder.value())));
        return !entries.isEmpty();
    }

    /** 从当刻的世界拿数据包注册表；不在世界里给空。 */
    private <T> Optional<Registry<T>> worldRegistry(ResourceKey<Registry<T>> key) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return Optional.empty();
        }
        return current.level().registryAccess().registry(key);
    }
}
