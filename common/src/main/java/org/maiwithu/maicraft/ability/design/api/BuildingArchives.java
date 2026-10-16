// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.PlannedCellCodec;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 建筑档案库：按世界把每一栋的档案存成一篇文档，键是档案名；另存一份名单，好按位置找"这一格属于哪一栋"。
 * 存不进去（库坏了、超预算）时保存返回假，施工照常结算，只是档案没留住；读不到按没有这一栋对待。
 */
public final class BuildingArchives {

    /** 文档库里的范围名：建筑档案都存这个范围下，与机器档案和别的用途互不覆盖。 */
    static final String SCOPE = "building-archive";
    /** 档案名单单独一个范围：名单的键不会和哪一栋的名字撞上。 */
    static final String NAMES_SCOPE = "building-archive-names";
    private static final String NAMES_KEY = "all";
    /** 一篇档案的存盘预算：一栋大房子几万格也够；超出时不写。 */
    private static final int DOCUMENT_LIMIT = 8 * 1024 * 1024;

    private final DocumentStore documents;
    private final String worldKey;

    public BuildingArchives(DocumentStore documents, String worldKey) {
        this.documents = documents;
        this.worldKey = worldKey;
    }

    /** 按名字找档案；没有这个名、或存的那篇读不出来时给空。 */
    public Optional<BuildingArchive> find(String name) {
        try {
            String json = documents.read(SCOPE, worldKey, key(name), DOCUMENT_LIMIT);
            return json == null ? Optional.empty() : Optional.of(decode(json));
        } catch (IOException | RuntimeException broken) {
            return Optional.empty();
        }
    }

    /** 存一份档案并记进名单；存不进去返回假，调用方把"档案没留住"记进结果，不因此改判施工。 */
    public boolean save(BuildingArchive archive) {
        try {
            String json = encode(archive);
            if (json.getBytes(StandardCharsets.UTF_8).length > DOCUMENT_LIMIT) return false;
            if (!documents.write(SCOPE, worldKey, key(archive.name()), json, false)) return false;
            Set<String> names = names();
            if (names.add(archive.name())) {
                JsonArray list = new JsonArray();
                names.forEach(list::add);
                documents.write(NAMES_SCOPE, worldKey, NAMES_KEY, list.toString(), false);
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /** 这个世界里存着的每一栋；读不出来的那几篇跳过。 */
    public List<BuildingArchive> all() {
        List<BuildingArchive> out = new ArrayList<>();
        for (String name : names()) find(name).ifPresent(out::add);
        return out;
    }

    /** 这一格落在哪一栋的范围里；落在好几栋里时取范围最小的那栋（贴得最近的那一栋）。 */
    public Optional<BuildingArchive> containing(String dimension, BlockPos pos) {
        return all().stream().filter(archive -> archive.contains(dimension, pos))
                .min(Comparator.comparingLong(archive -> archive.bounds().volume()));
    }

    /**
     * 起一个不撞的档案名：没人用就用它；同名的档案锚点就在这里（同一栋）照用；在别处就加上"@x,y,z"，不问。
     *
     * @param wanted    想用的名字
     * @param dimension 这一栋的维度
     * @param anchor    这一栋的锚点
     */
    public String unusedName(String wanted, String dimension, BlockPos anchor) {
        Optional<BuildingArchive> existing = find(wanted);
        if (existing.isEmpty() || existing.get().dimension().equals(dimension) && existing.get().anchor().equals(anchor)) return wanted;
        return wanted + "@" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
    }

    private Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        try {
            String json = documents.read(NAMES_SCOPE, worldKey, NAMES_KEY, DOCUMENT_LIMIT);
            if (json == null) return names;
            for (JsonElement name : JsonParser.parseString(json).getAsJsonArray()) names.add(name.getAsString());
        } catch (IOException | RuntimeException broken) {
            // 名单读不出来：当作还没有档案，按名字直接找的那条路照常。
        }
        return names;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // 档案的正文：整栋蓝图逐格照计划格的存盘写法存（机器档案也是这一份），读回后原样重建。
    private static String encode(BuildingArchive archive) {
        JsonObject root = new JsonObject();
        root.addProperty("name", archive.name());
        root.addProperty("dimension", archive.dimension());
        root.addProperty("anchor", archive.anchor().asLong());
        root.addProperty("rotation", archive.rotation());
        if (archive.designId() != null) root.addProperty("design_id", archive.designId());
        JsonArray cells = new JsonArray();
        for (PlannedCell cell : archive.cells()) cells.add(PlannedCellCodec.encode(cell));
        root.add("cells", cells);
        if (archive.lastCheckAt() != null) {
            root.addProperty("last_check_at", archive.lastCheckAt().toString());
            root.addProperty("last_check_note", archive.lastCheckNote());
        }
        return root.toString();
    }

    private static BuildingArchive decode(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<PlannedCell> cells = new ArrayList<>();
        for (JsonElement cell : root.getAsJsonArray("cells")) cells.add(PlannedCellCodec.decode(cell.getAsJsonObject()));
        return new BuildingArchive(root.get("name").getAsString(), root.get("dimension").getAsString(),
                BlockPos.of(root.get("anchor").getAsLong()), root.get("rotation").getAsInt(),
                root.has("design_id") ? root.get("design_id").getAsString() : null, cells,
                root.has("last_check_at") ? Instant.parse(root.get("last_check_at").getAsString()) : null,
                root.has("last_check_note") ? root.get("last_check_note").getAsString() : null);
    }
}
