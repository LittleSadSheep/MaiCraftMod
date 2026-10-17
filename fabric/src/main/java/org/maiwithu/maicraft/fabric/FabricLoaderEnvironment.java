// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.loader.api.FabricLoader;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;

import java.nio.file.Path;
import java.util.Optional;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import net.fabricmc.fabric.api.registry.FuelRegistry;

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

    // Fabric 的模组燃料登记在 Fabric API 的燃料登记表里（原版燃料也在其中）：按物品查能烧多少刻，没登记为 0。
    @Override public FurnaceFuels furnaceFuels() {
        return stack -> {
            Integer ticks = FuelRegistry.INSTANCE.get(stack.getItem());
            return ticks == null ? 0 : ticks;
        };
    }
}
