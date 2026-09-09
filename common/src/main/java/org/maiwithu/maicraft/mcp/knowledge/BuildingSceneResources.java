// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;

/**
 * 通过现有知识资源地址提供完整模型或编译后的蓝图，任务结果只需返回资源链接，不塞入全部格子。
 */
public final class BuildingSceneResources {
    public static final String PREFIX = "maicraft://knowledge/build/";
    private BuildingSceneResources() {}

    public static String sceneUri(String id) { return PREFIX + "scene/" + id; }
    public static String blueprintUri(String id) { return PREFIX + "blueprint/" + id; }

    // 只处理 build/scene 和 build/blueprint 两种地址；必须在已识别的当前世界和相同维度读取，不能跨世界借用编号。
    static KnowledgeDocument read(String uri) {
        if (!uri.startsWith(PREFIX)) return null;
        String[] parts = uri.substring(PREFIX.length()).split("/", -1);
        if (parts.length != 2 || !java.util.Set.of("scene", "blueprint").contains(parts[0])) throw KnowledgeException.missing(uri);
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) throw KnowledgeException.missing(uri);
        var entry = BuildingSceneStore.current().load(parts[1], minecraft.level.dimension().location().toString());
        // 请求 scene 时给原对象数据；请求 blueprint 时现场编译并补齐导出状态。这些资料不证明施工已经完成。
        var document = parts[0].equals("scene") ? entry.scene()
                : org.maiwithu.maicraft.core.blueprint.BuildingSceneBlocks.export(BuildingSceneCompiler.compile(entry.scene()));
        return new KnowledgeDocument(uri, "build." + parts[0], "Building model " + entry.sceneId(),
                "Retained authored model; does not prove construction completion", document.toString(), "application/json");
    }
}
