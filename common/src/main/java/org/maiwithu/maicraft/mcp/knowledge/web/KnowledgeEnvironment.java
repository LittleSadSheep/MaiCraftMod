// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonObject;
import java.util.Map;

/** 客户端启动时冻结加载器与模组版本，资料线程无需访问玩家、世界或服务端私有配置。 */
public final class KnowledgeEnvironment {
    private record Snapshot(String loader, String minecraft, Map<String, String> mods) {}
    private static volatile Snapshot current = new Snapshot("unknown", "unknown", Map.of());
    private KnowledgeEnvironment() {}

    public static void install(String loader, String minecraft, Map<String, String> mods) {
        current = new Snapshot(loader, minecraft, Map.copyOf(mods));
    }

    static JsonObject capture(String subject) {
        Snapshot snapshot = current;
        JsonObject result = new JsonObject();
        result.addProperty("edition", "java"); result.addProperty("minecraft_version", snapshot.minecraft());
        result.addProperty("loader", snapshot.loader()); result.addProperty("version_scope", "client_installation");
        if (subject != null) {
            String namespace = subject.substring(0, subject.indexOf(':'));
            result.addProperty("subject_id", subject);
            result.addProperty("subject_mod_version", namespace.equals("minecraft") ? snapshot.minecraft() : snapshot.mods().getOrDefault(namespace, "unknown"));
        }
        // 联机整合包可能修改配方；客户端版本是比较线索，不是当前服务器实际配方的证明。
        result.addProperty("server_configuration_verified", false);
        return result;
    }
}
