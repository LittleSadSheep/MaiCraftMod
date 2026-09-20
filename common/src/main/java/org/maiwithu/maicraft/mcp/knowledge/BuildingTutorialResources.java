// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;

/** 建房教材与编译契约分别版本化；发现目录不启动角色，正文由设计任务按需注入。 */
final class BuildingTutorialResources {
    private static final String ROOT = "/assets/maicraft/knowledge/building/";
    private BuildingTutorialResources() {}

    private record Tutorial(JsonObject declaration, KnowledgeDocument document, String revision) {
        JsonObject metadata(String schemaRevision) {
            var row = declaration.deepCopy(); row.remove("id");
            row.addProperty("uri", document.uri()); row.addProperty("revision", revision);
            var compatible = new JsonArray(); compatible.add(schemaRevision);
            row.add("compatible_schema_revisions", compatible);
            return row;
        }
    }

    private static final class Bundled {
        // 只在首次发现教材时读取随包静态文本以计算指纹；不读取世界、库存或玩家状态。
        private static final List<Tutorial> TUTORIALS = load();
    }

    static JsonArray catalog(BuildingModelContract.Snapshot contract) {
        var rows = new JsonArray();
        for (var tutorial : Bundled.TUTORIALS) rows.add(tutorial.metadata(contract.designSchemaRevision()));
        return rows;
    }

    static List<KnowledgeDocument.Entry> entries() {
        return Bundled.TUTORIALS.stream().map(tutorial -> tutorial.document().entry()).toList();
    }

    static KnowledgeDocument read(String uri) {
        return Bundled.TUTORIALS.stream().map(Tutorial::document)
                .filter(document -> document.uri().equals(uri)).findFirst().orElse(null);
    }

    private static List<Tutorial> load() {
        var tutorials = new ArrayList<Tutorial>(); var ids = new HashSet<String>();
        for (var value : JsonParser.parseString(text("catalog.json")).getAsJsonArray()) {
            var declaration = value.getAsJsonObject(); String id = declaration.get("id").getAsString();
            if (!id.matches("house/[a-z0-9-]+(/[a-z0-9-]+)*") || !ids.add(id))
                throw new IllegalStateException("Invalid or duplicate building tutorial id: " + id);
            String content = text(id + ".md"); String revision = digest(content);
            // URI 指向这份完整正文；更新教材只换教材地址，不让旧场景失去编译契约凭据。
            String uri = "maicraft://building/" + id + "/" + revision.substring("sha256:".length());
            var document = new KnowledgeDocument(uri, "building." + id.replace('/', '.'),
                    declaration.get("title").getAsString(), declaration.get("summary").getAsString(), content);
            tutorials.add(new Tutorial(declaration, document, revision));
        }
        return List.copyOf(tutorials);
    }

    private static String text(String path) {
        try (var input = BuildingTutorialResources.class.getResourceAsStream(ROOT + path)) {
            if (input == null) throw new IllegalStateException("Missing building tutorial: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read building tutorial: " + path, failure);
        }
    }

    private static String digest(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
