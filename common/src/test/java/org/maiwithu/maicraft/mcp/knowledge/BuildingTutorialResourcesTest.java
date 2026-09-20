// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneBlocks;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneExport;
import net.minecraft.nbt.Tag;
import static org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest.*;

/** 教材随包发布；所有围栏 JSON 都是可编译场景，实体、明确空气和未声明留白分别核验。 */
public final class BuildingTutorialResourcesTest {
    public static void main(String[] args) throws Exception {
        var library = KnowledgeLibrary.offline(); var contract = BuildingModelContract.current();
        var index = JsonParser.parseString(library.read(BuildingModelContract.INDEX_URI).text()).getAsJsonObject();
        var listed = new HashSet<String>(); var listing = request("list");
        while (true) {
            var page = library.request(listing);
            page.getAsJsonArray("resources").forEach(value -> listed.add(value.getAsJsonObject().get("uri").getAsString()));
            if (!page.has("nextCursor")) break; listing.add("cursor", page.get("nextCursor"));
        }
        var examples = new HashMap<String, Map<String, JsonObject>>(); var evidence = new JsonArray();
        int startup = 0;
        for (var value : index.getAsJsonArray("resources")) {
            var entry = value.getAsJsonObject(); String uri = entry.get("uri").getAsString();
            check(listed.contains(uri), "教程可通过标准分页发现: " + uri);
            String text = library.read(uri).text();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            check(uri.endsWith("/" + hash) && entry.get("revision").getAsString().equals("sha256:" + hash), "地址与版本须对应完整正文");
            check(!text.isBlank() && text.length() < 24000, "单份教材应保留完整正文并适合按需注入");
            if (entry.get("load_policy").getAsString().equals("task_start")) startup++;
            else check(entry.get("load_policy").getAsString().equals("on_demand"), "未知教材读取策略");
            for (var capability : entry.getAsJsonArray("requires"))
                check(contract.capabilities().contains(capability.getAsString()), "教材要求真实存在的能力");
            check(entry.getAsJsonArray("compatible_schema_revisions").get(0).getAsString().equals(contract.designSchemaRevision()), "教材声明当前受测格式");
            var exported = entry.deepCopy(); exported.addProperty("text", text); var scenes = new JsonArray();
            var fences = Pattern.compile("(?m)^```json\\R([\\s\\S]*?)^```\\s*$").matcher(text);
            while (fences.find()) {
                var scene = JsonParser.parseString(fences.group(1)).getAsJsonObject();
                BuildingSceneCompiler.validateWire(scene); var blueprint = BuildingSceneCompiler.compile(scene);
                var blocks = new HashMap<String, JsonObject>();
                for (var block : blueprint.getAsJsonArray("blocks")) {
                    var cell = block.getAsJsonObject(); BuildingSceneBlocks.resolve(cell);
                    check(blocks.put(cell.get("offset").toString(), cell) == null, "最终蓝图不能重复坐标");
                }
                check(!blocks.isEmpty(), "教材不能交付空模型");
                check(BuildingSceneExport.structure(blueprint).getList("blocks", Tag.TAG_COMPOUND).size() == blocks.size(), "导出不能丢目标");
                String name = scene.get("name").getAsString(); check(examples.put(name, blocks) == null, "案例名字须唯一");
                var example = new JsonObject(); example.add("scene", scene); example.add("blueprint", blueprint); scenes.add(example);
            }
            exported.add("examples", scenes); evidence.add(exported);
            try { library.read(uri.substring(0, uri.lastIndexOf('/') + 1) + "0".repeat(64)); throw new AssertionError("未知教材版本被重定向"); }
            catch (KnowledgeException expected) { check(expected.code() == -32002, "未知内容版本应明确不存在"); }
        }
        check(startup == 1 && examples.size() >= 11, "基础方法只注入一次，其余风格和结构按需选择");
        check(contract.revision().equals(index.get("revision").getAsString()) && contract.index().getAsJsonArray("resources").isEmpty(), "编辑知识目录不能污染几何编译契约");
        for (String query : new String[]{"欧式", "网状", "通行"}) {
            var search = request("search"); search.addProperty("query", query);
            var found = library.request(search);
            check(found.get("total_matches").getAsInt() > 0 && !found.get("content_loaded").getAsBoolean(), "关键词发现只返回教材摘要");
        }
        geometry(examples);
        // 可选证据目录用于外部 JSON Schema 校验和可视检查，永远不生成施工任务或读写世界。
        String output = System.getProperty("maicraft.building.tutorial.output");
        if (output != null) {
            var directory = Path.of(output); Files.createDirectories(directory);
            Files.writeString(directory.resolve("tutorials.json"), evidence.toString());
            Files.writeString(directory.resolve("index.json"), index.toString());
            Files.writeString(directory.resolve("schema.json"), contract.schemaText());
        }
        System.out.println("BuildingTutorialResourcesTest: " + evidence.size() + " resources and " + examples.size() + " compiled examples passed");
    }

    private static void geometry(Map<String, Map<String, JsonObject>> examples) {
        // 检查门洞、室内空区和山墙；合法 JSON 不能掩盖房间被屋顶填满这一设计错误。
        block(examples, "RoomSkeleton", 3, 1, 0, "air"); block(examples, "RoomSkeleton", 2, 1, 0, "oak_planks");
        block(examples, "RoomSkeleton", 3, 0, 0, "stone_bricks"); block(examples, "RoomSkeleton", 3, 4, 3, "air");
        block(examples, "GabledRoof", 3, 9, 3, "deepslate_tiles"); unspecified(examples, "GabledRoof", 3, 6, 3);
        block(examples, "GabledRoof", 3, 8, 0, "white_terracotta"); block(examples, "GabledRoof", -1, 5, 3, "deepslate_tiles");
        block(examples, "RecessedWindow", 4, 3, 1, "glass"); unspecified(examples, "RecessedWindow", 4, 3, 0);
        block(examples, "RecessedWindow", 2, 3, 0, "stone_bricks");
        block(examples, "LatticeScreen", 2, 2, 0, "air"); block(examples, "LatticeScreen", 1, 2, 0, "dark_oak_planks");
        property(examples, "AlternatingSlabs", 0, 0, 0, "type", "bottom");
        property(examples, "AlternatingSlabs", 1, 0, 0, "type", "top");
        property(examples, "AlternatingSlabs", 0, 1, 0, "type", "top");
        for (int x : new int[]{2, 6, 10}) block(examples, "TimberWindowBays", x, 2, 1, "glass");
        property(examples, "TimberWindowBays", 2, 5, 0, "axis", "x");
        property(examples, "TimberWindowBays", 0, 2, 0, "axis", "y");
        block(examples, "SteppedStonePortal", 4, 5, 0, "air"); block(examples, "SteppedStonePortal", 3, 5, 0, "stone_bricks");
        unspecified(examples, "CourtyardGallery", 6, 1, 2); block(examples, "CourtyardGallery", 6, 0, 2, "stone_bricks");
        block(examples, "CourtyardGallery", 4, 1, 0, "stripped_spruce_log");
        block(examples, "ModernShadedWindow", 4, 3, 1, "light_gray_stained_glass"); block(examples, "ModernShadedWindow", 4, 3, 0, "air");
        block(examples, "ModernShadedWindow", 4, 6, -2, "white_concrete");
        for (int x = 4; x <= 8; x++) for (int y = 0; y < 5; y++) {
            unspecified(examples, "WorkshopEndWall", x, y, 0);
            block(examples, "WorkshopEndWall", x, y, 1, "air");
        }
        block(examples, "WorkshopEndWall", 1, 6, 1, "glass"); block(examples, "WorkshopEndWall", 10, 6, 1, "glass");
        // 完整小屋必须同时有上下门半部、四面采光和连续空区，不能只验证外立面的一个窗口。
        property(examples, "TimberCottage", 3, 1, 0, "half", "lower");
        property(examples, "TimberCottage", 3, 2, 0, "half", "upper");
        for (int[] point : new int[][]{{1,2,0},{3,2,6},{0,2,3},{6,2,3}})
            block(examples, "TimberCottage", point[0], point[1], point[2], "glass");
        for (int z = -2; z < 0; z++) for (int y = 1; y <= 2; y++) unspecified(examples, "TimberCottage", 3, y, z);
        for (int z = 1; z <= 5; z++) for (int y = 1; y <= 2; y++)
            block(examples, "TimberCottage", 3, y, z, "air");
        unspecified(examples, "TimberCottage", 3, 6, 3); block(examples, "TimberCottage", 3, 9, 3, "dark_oak_planks");
        // 檐口外挑时，侧墙顶和上一级屋面之间仍需连续封口，不能只靠两端山墙收边。
        for (int x : new int[]{0, 6}) for (int z = 1; z <= 5; z++) {
            block(examples, "GabledRoof", x, 5, z, "white_terracotta");
            block(examples, "TimberCottage", x, 5, z, "spruce_log");
        }
    }

    private static JsonObject cell(Map<String, Map<String, JsonObject>> examples, String name, int x, int y, int z) {
        check(examples.containsKey(name), "必须编译实际发布的案例: " + name);
        return examples.get(name).get("[" + x + "," + y + "," + z + "]");
    }
    private static void block(Map<String, Map<String, JsonObject>> examples, String name, int x, int y, int z, String expected) {
        var cell = cell(examples, name, x, y, z);
        check(cell != null, name + " 缺少明确目标: " + x + "," + y + "," + z);
        String actual = cell.get("block_id").getAsString();
        check(actual.equals("minecraft:" + expected), name + " 在 " + x + "," + y + "," + z + " 应为 " + expected + "，实际 " + actual);
    }
    private static void unspecified(Map<String, Map<String, JsonObject>> examples, String name, int x, int y, int z) {
        // 模型没有覆盖的坐标只代表依赖场地留白，不能把它计为会清理旧方块的空气目标。
        check(cell(examples, name, x, y, z) == null, name + " 的留白不应暗中变成实体或清障目标");
    }
    private static void property(Map<String, Map<String, JsonObject>> examples, String name, int x, int y, int z, String key, String expected) {
        var cell = cell(examples, name, x, y, z);
        check(cell != null && cell.getAsJsonObject("properties").get(key).getAsString().equals(expected), name + " 必须保留 " + key + "=" + expected);
    }
}
