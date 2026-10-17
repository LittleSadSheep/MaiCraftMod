// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.PlannedCellCodec;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 机器档案库：按世界把每台机器的档案存成一篇文档，键是档案名。
 * 存不进去（库坏了、超预算）时保存返回假，施工照常结算，只是档案没留住；读不到按没有这台机器对待。
 */
final class MachineArchives {

    /** 文档库里的范围名：机器档案都存这个范围下，与别的用途互不覆盖。 */
    static final String SCOPE = "machine-archive";

    /** 一篇档案的存盘预算：一台机器几十到几百格的蓝图加机器条目，远用不到这么大；超出时不写。 */
    private static final int DOCUMENT_LIMIT = 2 * 1024 * 1024;

    private final DocumentStore documents;
    private final String worldKey;

    MachineArchives(DocumentStore documents, String worldKey) {
        this.documents = documents;
        this.worldKey = worldKey;
    }

    /** 按名字找档案；没有这个名、或存的那篇读不出来时给空。 */
    Optional<MachineArchive> find(String name) {
        try {
            String json = documents.read(SCOPE, worldKey, key(name), DOCUMENT_LIMIT);
            return json == null ? Optional.empty() : Optional.of(decode(name, json));
        } catch (IOException | RuntimeException broken) {
            return Optional.empty();
        }
    }

    /** 存一份档案；存不进去返回假，调用方把"档案没留住"记进结果，不因此改判施工。 */
    boolean save(MachineArchive archive) {
        try {
            String json = encode(archive);
            if (json.getBytes(StandardCharsets.UTF_8).length > DOCUMENT_LIMIT) {
                return false;
            }
            return documents.write(SCOPE, worldKey, key(archive.name()), json, false);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // 档案的正文：蓝图逐格照计划格的存盘写法存（建筑档案也是这一份），读回后原样重建。
    private static String encode(MachineArchive archive) {
        JsonObject root = new JsonObject();
        root.addProperty("name", archive.name());
        root.addProperty("dimension", archive.dimension());
        root.addProperty("anchor", archive.anchor().asLong());
        root.add("blueprint", encodeBlueprint(archive.blueprint()));
        if (archive.designId() != null) {
            root.addProperty("design_id", archive.designId());
        }
        root.addProperty("removed", archive.removed());
        JsonObject networks = new JsonObject();
        archive.networks().forEach(networks::addProperty);
        root.add("networks", networks);
        if (archive.lastCheckAt() != null) {
            root.addProperty("last_check_at", archive.lastCheckAt().toString());
            root.addProperty("last_check_note", archive.lastCheckNote());
        }
        return root.toString();
    }

    private static MachineArchive decode(String name, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        BlockPos anchor = BlockPos.of(root.get("anchor").getAsLong());
        MachineBlueprint blueprint = decodeBlueprint(root.getAsJsonObject("blueprint"));
        String designId = root.has("design_id") ? root.get("design_id").getAsString() : null;
        Map<String, String> networks = new LinkedHashMap<>();
        if (root.has("networks")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("networks").entrySet()) {
                networks.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return new MachineArchive(name, root.get("dimension").getAsString(), anchor, blueprint, designId,
                root.get("removed").getAsBoolean(), networks,
                root.has("last_check_at") ? Instant.parse(root.get("last_check_at").getAsString()) : null,
                root.has("last_check_note") ? root.get("last_check_note").getAsString() : null);
    }

    private static JsonObject encodeBlueprint(MachineBlueprint blueprint) {
        JsonObject body = new JsonObject();
        JsonArray cells = new JsonArray();
        for (PlannedCell cell : blueprint.cells()) {
            cells.add(PlannedCellCodec.encode(cell));
        }
        body.add("cells", cells);
        body.add("parts", MachineBlueprintCodec.parts(blueprint));
        body.add("installations", MachineBlueprintCodec.installations(blueprint));
        body.add("settings", MachineBlueprintCodec.settings(blueprint));
        body.add("processes", MachineBlueprintCodec.processes(blueprint));
        return body;
    }

    private static MachineBlueprint decodeBlueprint(JsonObject body) {
        List<PlannedCell> cells = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("cells")) {
            cells.add(PlannedCellCodec.decode(element.getAsJsonObject()));
        }
        return new MachineBlueprint(cells,
                MachineBlueprintCodec.readParts(body.getAsJsonArray("parts")),
                MachineBlueprintCodec.readInstallations(body.getAsJsonArray("installations")),
                MachineBlueprintCodec.readSettings(body.getAsJsonArray("settings")),
                MachineBlueprintCodec.readProcesses(body.getAsJsonArray("processes")));
    }
}
