// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.loader.api.FabricLoader;
import org.maiwithu.maicraft.platform.loader.LoaderEnvironment;

import java.nio.file.Path;
import java.util.Optional;

/** Fabric 对加载器环境的实现：把 FabricLoader 的查询翻译成公共代码使用的环境事实。 */
final class FabricLoaderEnvironment implements LoaderEnvironment {

    @Override public String loaderName() {
        return "fabric";
    }

    @Override public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override public Optional<String> modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString());
    }

    @Override public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override public boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
