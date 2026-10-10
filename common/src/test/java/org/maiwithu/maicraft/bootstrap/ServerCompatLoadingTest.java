// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/** 服务端联动清单逐行检查：没装、版本不对、创建出错都不登记，也不影响别的模组；通过的把只读操作登记上。 */
class ServerCompatLoadingTest {

    private static final VerifiedVersions TESTED = new VerifiedVersions("19.2.17", "19.3");

    /** 装了哪些模组由测试摆。 */
    private record Loader(Map<String, String> installed) implements LoaderEnvironment {
        @Override public String loaderName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return installed.containsKey(modId); }
        @Override public Optional<String> modVersion(String modId) { return Optional.ofNullable(installed.get(modId)); }
        @Override public Path gameDirectory() { return Path.of("."); }
        @Override public Path configDirectory() { return Path.of("."); }
        @Override public boolean isDevelopment() { return true; }
        @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
    }

    /** 登记一个只读操作的服务端联动入口。 */
    private static SupportedMod<ServerCompatModule> answering(String modId, String operation) {
        return new SupportedMod<>(modId, "测试模组 " + modId, TESTED, () -> new ServerCompatModule(modId, "测试模组 " + modId) {
            @Override public void contribute(ServerCompatRegistry registry) {
                registry.readOperation(this, operation, 1, (player, body) -> new JsonObject());
            }
        });
    }

    @Test
    void 逐行检查_通过的登记操作_出问题的写明原因() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        SupportedMod<ServerCompatModule> broken = new SupportedMod<>("broken", "坏模组", TESTED, () -> {
            throw new NoClassDefFoundError("SomeMod");
        });
        List<String> lines = ServerCompatLoading.load(
                List.of(answering("missing", "missing.read"), answering("old", "old.read"), broken,
                        answering("ae2", "ae2.network")),
                new Loader(Map.of("old", "19.1.0", "broken", "19.2.17", "ae2", "19.2.17")),
                new ServerCompatRegistry(operations));
        assertEquals("测试模组 missing（missing）：没装，跳过", lines.get(0));
        assertTrue(lines.get(1).contains("装的是 19.1.0"), lines.get(1));
        assertTrue(lines.get(2).contains("创建时出错，不登记") && lines.get(2).contains("NoClassDefFoundError"), lines.get(2));
        assertEquals("测试模组 ae2（ae2）：已登记，版本 19.2.17", lines.get(3));
        assertTrue(operations.registered("ae2.network"));
        assertFalse(operations.registered("old.read"));
    }
}
