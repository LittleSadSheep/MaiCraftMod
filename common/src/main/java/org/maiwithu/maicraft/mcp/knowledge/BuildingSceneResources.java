// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;

/** Reuse the existing knowledge/resource channel for complete model JSON without bloating task receipts. */
public final class BuildingSceneResources {
    public static final String PREFIX = "maicraft://knowledge/build/";
    private BuildingSceneResources() {}

    public static String sceneUri(String id) { return PREFIX + "scene/" + id; }
    public static String blueprintUri(String id) { return PREFIX + "blueprint/" + id; }

    static KnowledgeDocument read(String uri) {
        if (!uri.startsWith(PREFIX)) return null;
        String[] parts = uri.substring(PREFIX.length()).split("/", -1);
        if (parts.length != 2 || !java.util.Set.of("scene", "blueprint").contains(parts[0])) throw KnowledgeException.missing(uri);
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) throw KnowledgeException.missing(uri);
        var entry = BuildingSceneStore.current().load(parts[1], minecraft.level.dimension().location().toString());
        var document = parts[0].equals("scene") ? entry.scene() : BuildingSceneCompiler.compile(entry.scene());
        return new KnowledgeDocument(uri, "build." + parts[0], "Building model " + entry.sceneId(),
                "Retained authored model; does not prove construction completion", document.toString(), "application/json");
    }
}
