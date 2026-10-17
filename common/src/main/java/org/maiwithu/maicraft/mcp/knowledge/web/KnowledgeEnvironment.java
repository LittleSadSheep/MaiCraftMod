// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.TreeMap;

/**
 * 客户端启动时冻结的加载器与模组版本清单：资料线程据此标注条目所属模组的版本，
 * 无需访问玩家、世界或服务端配置。
 */
public final class KnowledgeEnvironment {
    private record Snapshot(String loader, String minecraft, Map<String, String> mods, boolean known) {}

    private volatile Snapshot current = new Snapshot("unknown", "unknown", Map.of(), false);

    /** 启动时登记一次；清单冻结后资料线程只读，不再触碰世界或玩家状态。 */
    public void install(String loader, String minecraft, Map<String, String> mods) {
        current = new Snapshot(loader, minecraft, Map.copyOf(mods), true);
    }

    JsonObject capture(String subject) {
        return describe(current, subject);
    }

    /**
     * 宿主据此决定哪些模组玩法值得准备：列出客户端实际加载的全部模组编号与版本。
     * 清单在启动时冻结，只读且不触碰世界；加载器尚未登记时如实标为未知，空清单不能被理解成“没装模组”。
     */
    public JsonObject installation() {
        Snapshot snapshot = current;
        JsonObject result = describe(snapshot, null);
        result.addProperty("mods_known", snapshot.known());
        JsonObject mods = new JsonObject();
        // 按模组编号排序，宿主两次读取同一安装时得到逐字相同的清单。
        new TreeMap<>(snapshot.mods()).forEach(mods::addProperty);
        result.add("mods", mods);
        return result;
    }

    private static JsonObject describe(Snapshot snapshot, String subject) {
        JsonObject result = new JsonObject();
        result.addProperty("edition", "java");
        result.addProperty("minecraft_version", snapshot.minecraft());
        result.addProperty("loader", snapshot.loader());
        result.addProperty("version_scope", "client_installation");
        if (subject != null) {
            String namespace = subject.substring(0, subject.indexOf(':'));
            result.addProperty("subject_id", subject);
            result.addProperty("subject_mod_version",
                    namespace.equals("minecraft") ? snapshot.minecraft() : snapshot.mods().getOrDefault(namespace, "unknown"));
        }
        // 联机整合包可能修改配方；客户端版本是比较线索，不是当前服务器实际配方的证明。
        result.addProperty("server_configuration_verified", false);
        return result;
    }
}
