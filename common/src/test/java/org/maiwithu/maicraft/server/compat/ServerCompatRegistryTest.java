// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.ServerOperationRegistry;

/** 服务端联动登记表：只读操作交接成功才登记，撞名或出错不留半个模组；停用、对不上时按"没执行"回答。 */
class ServerCompatRegistryTest {

    private static final BiFunction<ServerPlayer, JsonObject, JsonObject> ANSWER = (player, body) -> {
        JsonObject answer = new JsonObject();
        answer.addProperty("channels", 8);
        return answer;
    };

    /** 交什么由测试写在 contribute 里的服务端联动入口。 */
    private abstract static class Contributing extends ServerCompatModule {
        Contributing() {
            super("ae2", "应用能源2");
        }
    }

    @Test
    void 交接成功的只读操作进注册表() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        ServerCompatRegistry registry = new ServerCompatRegistry(operations);
        registry.add(new Contributing() {
            @Override public void contribute(ServerCompatRegistry registry) {
                registry.readOperation(this, "ae2.network", 1, ANSWER);
            }
        });
        assertTrue(operations.registered("ae2.network"));
        assertEquals(1, registry.modules().size());
    }

    @Test
    void 交接到一半出错或撞名时一个操作都不登记() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        operations.register("create.network", 1, false, ANSWER);
        ServerCompatRegistry registry = new ServerCompatRegistry(operations);
        assertThrows(IllegalStateException.class, () -> registry.add(new Contributing() {
            @Override public void contribute(ServerCompatRegistry registry) {
                registry.readOperation(this, "ae2.network", 1, ANSWER);
                throw new IllegalStateException("读写端建不起来");
            }
        }));
        assertThrows(IllegalStateException.class, () -> registry.add(new Contributing() {
            @Override public void contribute(ServerCompatRegistry registry) {
                registry.readOperation(this, "ae2.network", 1, ANSWER);
                registry.readOperation(this, "create.network", 1, ANSWER);
            }
        }));
        assertFalse(operations.registered("ae2.network"), "交了一半的操作要撤掉");
        assertTrue(registry.modules().isEmpty());
    }

    @Test
    void 交接之外登记操作直接报错() {
        ServerCompatRegistry registry = new ServerCompatRegistry(new ServerOperationRegistry());
        Contributing module = new Contributing() {
            @Override public void contribute(ServerCompatRegistry registry) {}
        };
        assertThrows(IllegalStateException.class, () -> registry.readOperation(module, "ae2.network", 1, ANSWER));
    }

    @Test
    void 接口对不上时停用_之后按没执行回答() {
        Contributing module = new Contributing() {
            @Override public void contribute(ServerCompatRegistry registry) {}
        };
        BiFunction<ServerPlayer, JsonObject, JsonObject> fragile = ServerCompatRegistry.guarded(module, "ae2.network",
                (player, body) -> module.call("读 ME 网络摘要", () -> {
                    throw new NoSuchMethodError("IGrid.getService");
                }));
        ServerOperationException first = assertThrows(ServerOperationException.class,
                () -> fragile.apply(null, new JsonObject()));
        assertEquals("mod_api_mismatch", first.code());
        assertFalse(module.active());
        ServerOperationException later = assertThrows(ServerOperationException.class,
                () -> ServerCompatRegistry.guarded(module, "ae2.network", ANSWER).apply(null, new JsonObject()));
        assertEquals("mod_disabled", later.code());
        assertTrue(later.getMessage().contains("读 ME 网络摘要"), later.getMessage());
    }
}
