// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.tags.BlockTags;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 身上有什么的读端合集：副手、物品标签、角色位置、物品注册表与工具要求，
 * 都从当刻的角色与游戏的注册表里读。清点身上、按标签点名、挑工具都认这一份，
 * 同一条游戏事实不在这包之外再读一遍游戏。
 */
public final class LiveCarryReads {

    private LiveCarryReads() {}

    /** 副手内容的生产实现：副手那一格按背包同一套快照给。 */
    public static OffhandContents offhand(Supplier<PlayerContext> context) {
        return new OffhandContents() {
            @Override
            public Optional<BackpackStack> heldInOffhand() {
                PlayerContext current = context.get();
                if (current == null || current.localPlayer() == null) {
                    return Optional.empty();
                }
                ItemStack offhand = current.localPlayer().getOffhandItem();
                return offhand.isEmpty() ? Optional.empty()
                        : Optional.of(ClientBackpackView.snapshot(offhand));
            }
        };
    }

    /** 物品标签的生产实现：一个物品挂着的全部标签，从游戏的注册表读。 */
    public static ReadsItemTags itemTags() {
        return itemId -> BuiltInRegistries.ITEM.getOptional(parse(itemId))
                .map(item -> {
                    Set<String> names = new LinkedHashSet<>();
                    // 遍历物品挂着的标签键：注册表持有者直接给全部标签，不用猜哪个命名空间。
                    item.builtInRegistryHolder().tags()
                            .forEach(tag -> names.add(tag.location().toString()));
                    return Set.copyOf(names);
                })
                .orElse(Set.of());
    }

    /** 角色位置的生产实现：问价的来源们用它知道角色现在在哪。 */
    public static ReadsCharacterPosition characterPosition(Supplier<PlayerContext> context) {
        return () -> {
            PlayerContext current = context.get();
            if (current == null || current.localPlayer() == null) {
                return null;
            }
            var player = current.localPlayer();
            return WorldPosition.here(
                    player.getBlockX(), player.getBlockY(), player.getBlockZ());
        };
    }

    /** 物品注册表的生产实现：写岔的物品 ID 在这里拦下，不进世界。 */
    public static ReadsItemRegistry itemRegistry() {
        return new ReadsItemRegistry() {
            @Override
            public boolean itemExists(String itemId) {
                Optional<Item> found = BuiltInRegistries.ITEM.getOptional(parse(itemId));
                // AIR 与写岔了都算没有。
                return found.isPresent() && !new ItemStack(found.get()).isEmpty();
            }

            @Override
            public boolean tagHasItems(String tagId) {
                var key = TagKey.create(Registries.ITEM, parse(tagId.startsWith("#") ? tagId.substring(1) : tagId));
                return BuiltInRegistries.ITEM.getTag(key)
                        .map(set -> !set.stream().toList().isEmpty()).orElse(false);
            }
        };
    }

    /** 工具要求的生产实现：挖哪种方块要哪类工具、手上这件够不够格，按游戏自己的规则答。 */
    public static ReadsToolRequirements toolRequirements(Supplier<PlayerContext> context) {
        return new ReadsToolRequirements() {
            @Override
            public Optional<String> toolRequired(String blockTypeId) {
                var block = BuiltInRegistries.BLOCK.getOptional(parse(blockTypeId));
                if (block.isEmpty()) {
                    return Optional.empty();
                }
                var state = block.get().defaultBlockState();
                // 不需要特定工具的方块（泥土、木头徒手也掉落）不给要求。
                if (!state.requiresCorrectToolForDrops()) {
                    return Optional.empty();
                }
                if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
                    return Optional.of("#minecraft:mineable/axe");
                }
                if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                    return Optional.of("#minecraft:mineable/pickaxe");
                }
                if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
                    return Optional.of("#minecraft:mineable/shovel");
                }
                if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
                    return Optional.of("#minecraft:mineable/hoe");
                }
                return Optional.empty();
            }

            @Override
            public boolean sufficient(String toolItemId, String blockTypeId) {
                var tool = BuiltInRegistries.ITEM.getOptional(parse(toolItemId));
                var block = BuiltInRegistries.BLOCK.getOptional(parse(blockTypeId));
                if (tool.isEmpty() || block.isEmpty()) {
                    return false;
                }
                // 够不够格以"用它挖这格掉不掉落"为准：等级不够的镐敲石头不掉，就是不够格。
                return new ItemStack(tool.get()).isCorrectToolForDrops(block.get().defaultBlockState());
            }
        };
    }

    private static ResourceLocation parse(String id) {
        String plain = id.toLowerCase(Locale.ROOT);
        return ResourceLocation.parse(plain.startsWith("#") ? plain.substring(1) : plain);
    }
}
