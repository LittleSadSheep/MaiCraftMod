// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;

import java.nio.file.Path;
import java.util.Optional;

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
}
