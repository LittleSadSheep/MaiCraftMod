// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 此刻客户端手上的游戏配方表：从服务器同步来的全部配方（含模组的加工配方），以及读配方定义要用的注册表。
 *
 * @param all        全部配方
 * @param registries 把配方定义转成 JSON、读物品名字用的注册表
 */
public record GameRecipes(Collection<RecipeHolder<?>> all, HolderLookup.Provider registries) {

    public GameRecipes {
        all = List.copyOf(all);
        Objects.requireNonNull(registries, "registries");
    }

    /** 从角色所在的世界读：角色不在世界里时没有配方表。 */
    public static Supplier<Optional<GameRecipes>> fromPlayer(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return () -> {
            PlayerContext current = context.get();
            if (current == null || current.level() == null) {
                return Optional.empty();
            }
            return Optional.of(new GameRecipes(current.level().getRecipeManager().getRecipes(),
                    current.level().registryAccess()));
        };
    }
}
