// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 从客户端手上的注册表读本实例注册了哪些 ID：物品、方块、实体类型问游戏内置的注册表（含模组注册），
 * 群系与结构是数据包注册表，从角色当刻所在的世界拿。
 *
 * <p>服务端只把群系这类要同步的数据包注册表发给客户端，结构注册表不发，所以客户端通常读不到结构，
 * 这里如实给空，由用的人说明读不到，不拿一份名单顶替。只在客户端线程上调用；不写任何静态状态。
 */
public final class ClientRegisteredIds implements ReadsRegisteredIds {

    private final Supplier<PlayerContext> context;

    /** @param context 当刻的角色上下文：群系与结构从它所在的世界读，不在世界里给空 */
    public ClientRegisteredIds(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** 只读游戏内置注册表的一份：物品、方块、实体类型读得到，群系与结构一律给空；解析图纸这类不碰世界的地方用。 */
    public static ClientRegisteredIds builtIn() {
        return new ClientRegisteredIds(() -> null);
    }

    @Override public List<String> ids(IdKind kind) {
        return registry(kind).map(registry -> registry.keySet().stream().map(Object::toString).sorted().toList())
                .orElse(List.of());
    }

    @Override public List<String> tags(IdKind kind) {
        return registry(kind).map(ClientRegisteredIds::filledTags).orElse(List.of());
    }

    // 空标签要不了东西：一样注册的都不挂的标签不算有，不进目录也不拿来当建议。
    private static <T> List<String> filledTags(Registry<T> registry) {
        return registry.getTagNames()
                .filter(tag -> registry.getTag(tag).map(set -> set.size() > 0).orElse(false))
                .map(tag -> tag.location().toString()).sorted().toList();
    }

    private Optional<? extends Registry<?>> registry(IdKind kind) {
        return switch (kind) {
            case ITEM -> Optional.of(BuiltInRegistries.ITEM);
            case BLOCK -> Optional.of(BuiltInRegistries.BLOCK);
            case ENTITY_TYPE -> Optional.of(BuiltInRegistries.ENTITY_TYPE);
            case BIOME -> worldRegistry(Registries.BIOME);
            case STRUCTURE -> worldRegistry(Registries.STRUCTURE);
        };
    }

    /** 从当刻的世界拿数据包注册表；不在世界里、或这份注册表没同步到客户端都给空。 */
    private <T> Optional<Registry<T>> worldRegistry(ResourceKey<Registry<T>> key) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return Optional.empty();
        }
        return current.level().registryAccess().registry(key);
    }
}
