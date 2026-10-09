// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;

import java.nio.file.Path;
import java.util.Optional;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import net.minecraft.world.item.crafting.RecipeType;

/** NeoForge 对加载器环境的实现：把 ModList 与 FMLPaths 的查询翻译成公共代码使用的环境事实。 */
final class NeoForgeLoaderEnvironment implements LoaderEnvironment {

    @Override public String loaderName() {
        return "neoforge";
    }

    @Override public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override public Optional<String> modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(mod -> mod.getModInfo().getVersion().toString());
    }

    @Override public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override public boolean isDevelopment() {
        return !FMLEnvironment.production;
    }

    // NeoForge 的燃料在它自己的燃料数据表与物品扩展里，原版那张燃料表没有模组燃料：按物品堆问烧炼能烧多久。
    @Override public FurnaceFuels furnaceFuels() {
        return stack -> stack.getBurnTime(RecipeType.SMELTING);
    }
}
