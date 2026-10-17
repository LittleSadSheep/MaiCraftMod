// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 从方块注册表读标签。标签在进世界时由服务端同步给客户端并绑到注册表上，
 * 所以没进世界时什么标签都读不到，这里如实返回空，不拿方块 ID 名单顶替。
 */
public final class RegistryBlockTags implements ReadsBlockTags {

    @Override public Set<String> tagsOf(String blockType) {
        ResourceLocation id = ResourceLocation.tryParse(blockType);
        if (id == null) {
            return Set.of();
        }
        Optional<Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.getHolder(id);
        if (holder.isEmpty()) {
            return Set.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        holder.get().tags().forEach(tag -> tags.add(tag.location().toString()));
        return tags;
    }

    @Override public Set<String> blocksIn(String tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag);
        if (id == null) {
            return Set.of();
        }
        Optional<HolderSet.Named<Block>> members = BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, id));
        if (members.isEmpty()) {
            return Set.of();
        }
        Set<String> blocks = new LinkedHashSet<>();
        for (Holder<Block> holder : members.get()) {
            blocks.add(BuiltInRegistries.BLOCK.getKey(holder.value()).toString());
        }
        return blocks;
    }
}
