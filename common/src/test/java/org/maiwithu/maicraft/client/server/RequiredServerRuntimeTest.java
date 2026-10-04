// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;

/** 世界已经加载但服务端尚未确认时，正式 MCP 入口和游戏刻仍不能让 AI 开始观察或动作。 */
public final class RequiredServerRuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var runtime = MaiCraftRuntimeFacade.instance();
            var observation = new JsonObject();
            observation.addProperty("view", "surroundings");
            // 用正式入口提交感知、执行和聊天请求，证明它们在读取业务参数前就拒绝未确认的服务器。
            rejected(runtime.perceive(observation));
            rejected(runtime.execute(new JsonObject()));
            rejected(runtime.readChat());
            ClientRuntime.tick(Minecraft.getInstance());
            check(ClientRuntime.lastTickStage().equals("awaiting_required_server"),
                    "client tick must stop before world observation, F8 and automatic actions");
            check(world.blockUses() == 0, "unconfirmed server must not receive native block interactions");
        }
        System.out.println("RequiredServerRuntimeTest: passed");
    }

    private static void rejected(CompletionStage<JsonElement> request) {
        // 检查明确的安装提示，避免普通参数错误碰巧挡住请求而掩盖入服检查失效。
        try {
            request.toCompletableFuture().join();
            throw new AssertionError("unconfirmed server unexpectedly allowed a game request");
        } catch (CompletionException rejected) {
            check(rejected.getCause() instanceof IllegalStateException
                    && rejected.getCause().getMessage().contains("服务器必须安装 MaiCraft"),
                    "request must report missing server confirmation");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
