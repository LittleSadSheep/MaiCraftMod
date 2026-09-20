// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.util.List;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;

/** 建造 Agent 先发现当前契约，再读取固定内容的 Schema；目录读取不接管角色或创建施工任务。 */
public final class BuildingModelContractResources {
    private BuildingModelContractResources() {}

    static List<KnowledgeDocument.Entry> entries() {
        var current = BuildingModelContract.current();
        return List.of(new KnowledgeDocument.Entry(BuildingModelContract.INDEX_URI,"building.index","建筑建模契约",
                        "Current design schema, compiler revision and model capabilities; no construction authority.","building schema revision","application/json"),
                new KnowledgeDocument.Entry(current.schemaUri(),"building.schema","建筑场景 JSON Schema",
                        "Immutable content-addressed scene and named-edit format; runtime geometry validation is still required.","building model schema","application/schema+json"));
    }

    static KnowledgeDocument read(String uri) {
        if (!uri.startsWith("maicraft://building/")) return null;
        var current = BuildingModelContract.current();
        if (BuildingModelContract.INDEX_URI.equals(uri)) return new KnowledgeDocument(uri,"building.index","建筑建模契约",
                "Current model contract, not a proof of site feasibility",current.index().toString(),"application/json");
        if (current.schemaUri().equals(uri)) return new KnowledgeDocument(uri,"building.schema","建筑场景 JSON Schema",
                "Full format with local definitions",current.schemaText(),"application/schema+json");
        // 契约更换后不把旧 URI 偷换成新正文；客户端须重新发现目录，已经保存的场景不会因此被覆盖。
        throw KnowledgeException.missing(uri);
    }
}
