// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.game.ModIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例配置：启动游戏的所有者放在配置文件里的开关，整个进程只有这一份，启动时读一次。
 *
 * <p>文件是客户端运行目录下 config/maicraft.json，java.nio 直接读写，不经过加载器的配置库。
 * 文件不存在就生成默认内容；读不出来（不是 JSON、缺键、键值类型不对）按默认值收场并记警告，
 * 不让一份坏配置挡住启动。LLM 的任何工具都触不到这里的开关：它不经参数面，只经这个文件。
 *
 * <p>现在有两项：
 * <ul>
 * <li>「允许执行游戏命令」（allowGameCommands，默认 false）：开着时 chat 能力放行以 / 开头的消息，
 *     作为游戏命令以角色自己的权限交给服务器执行。</li>
 * <li>「自家人」（trustedPlayers，默认空）：所有者信任的玩家，写玩家名或 UUID。他们放的箱子、AE 网络这类存储，
 *     角色可以取用、存放；拆、改照旧当别人的。</li>
 * </ul>
 */
public final class InstanceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceConfig.class);

    /** 配置文件名，放在加载器给的配置目录（config）下。 */
    private static final String FILE_NAME = "maicraft.json";

    private final boolean allowGameCommands;
    private final List<String> trustedPlayers;

    private InstanceConfig(boolean allowGameCommands, List<String> trustedPlayers) {
        this.allowGameCommands = allowGameCommands;
        this.trustedPlayers = List.copyOf(trustedPlayers);
    }

    /** 默认一份：所有开关都关，没有自家人。 */
    public static InstanceConfig defaults() {
        return new InstanceConfig(false, List.of());
    }

    /** 自家人名单：玩家名或 UUID；没配置时为空。 */
    public List<String> trustedPlayers() {
        return trustedPlayers;
    }

    /** 是否允许角色执行游戏命令（以 / 开头的消息按命令发送）。 */
    public boolean allowGameCommands() {
        return allowGameCommands;
    }

    /** 读配置文件所在目录（config），返回这一份实例配置；目录指向的文件不在就生成默认内容。 */
    public static InstanceConfig read(Path configDirectory) {
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            writeDefault(file);
            return defaults();
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            boolean allow = json.has("allowGameCommands") && json.get("allowGameCommands").getAsBoolean();
            // 自家人名单缺了就是没有；写成别的类型或里面混了非字符串，按坏配置收场。
            List<String> trusted = new ArrayList<>();
            if (json.has("trustedPlayers")) {
                for (JsonElement entry : json.getAsJsonArray("trustedPlayers")) {
                    // 数字、布尔不当名字收：Gson 会把它们悄悄转成字符串，这里先认清是不是字符串。
                    if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("自家人名单里只能写玩家名或 UUID 字符串：" + entry);
                    }
                    String name = entry.getAsString().trim();
                    if (!name.isEmpty()) trusted.add(name);
                }
            }
            return new InstanceConfig(allow, trusted);
        } catch (Exception exception) {
            // 坏配置不挡启动：按全关的默认值跑，并把原因记下来让所有者能发现。
            LOG.warn("{} 配置文件读不出来，按默认值（全部关闭）运行：{}", ModIdentity.NAME, file, exception);
            return defaults();
        }
    }

    /** 生成默认配置文件：全部关闭，让所有者有一份可以照着改的样子。 */
    private static void writeDefault(Path file) {
        JsonObject json = new JsonObject();
        json.addProperty("allowGameCommands", false);
        json.add("trustedPlayers", new JsonArray());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString() + "\n", StandardCharsets.UTF_8);
            LOG.info("{} 配置文件不存在，已生成默认配置：{}", ModIdentity.NAME, file);
        } catch (IOException exception) {
            // 写不出默认文件只影响所有者以后改配置，不影响这次启动，记警告继续。
            LOG.warn("{} 默认配置文件写不出来：{}", ModIdentity.NAME, file, exception);
        }
    }
}
