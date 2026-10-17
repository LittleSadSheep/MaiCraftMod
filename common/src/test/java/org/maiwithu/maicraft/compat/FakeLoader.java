// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/** 加载器环境的替身：按测试摆的"装了哪些模组、什么版本"回答。 */
final class FakeLoader implements LoaderEnvironment {
    private final Map<String, String> installed = new HashMap<>();

    FakeLoader with(String modId, String version) {
        installed.put(modId, version);
        return this;
    }

    @Override public String loaderName() {
        return "test";
    }

    @Override public boolean isModLoaded(String modId) {
        return installed.containsKey(modId);
    }

    @Override public Optional<String> modVersion(String modId) {
        return Optional.ofNullable(installed.get(modId));
    }

    @Override public Path gameDirectory() {
        return Path.of(".");
    }

    @Override public Path configDirectory() {
        return Path.of(".");
    }

    @Override public boolean isDevelopment() {
        return true;
    }

    @Override public FurnaceFuels furnaceFuels() {
        return stack -> 0;
    }
}
