// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneExport;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 独立以 2 GiB 堆验证大建筑的数据链路；只读预览不等于真实加载或渲染，更不启动身体施工。 */
public final class LargeBuildingBudgetTest {
    private static final int CELLS = 172_800, AIR = 147_264;
    private static final String WORLD = "d".repeat(64), DIMENSION = "minecraft:overworld";
    private static final Goal.WorldPosition ANCHOR = new Goal.WorldPosition(100, 65, -100, DIMENSION);
    private LargeBuildingBudgetTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("large-building-budget-");
        long total = System.nanoTime();
        try {
            BuildingBudgets.initialize(directory);
            check(BuildingBudgets.current().maxTargets() == 262_144, "独立验收应使用新默认目标预算");
            JsonObject scene = json("""
                    {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{"Shell":{"block_id":"minecraft:stone"}},
                     "objects":[{"name":"Hall","type":"MESH","primitive":"cube","location":[0,9,0],"dimensions":[120,18,80],
                       "material":"Shell","fill":"hollow","wall_thickness":1}]}
                    """);
            String authored = scene.toString(); long started = System.nanoTime();
            JsonObject blueprint = BuildingSceneCompiler.compile(scene);
            Summary expected = summarizeBlueprint(blueprint);
            check(expected.cells == CELLS && expected.air == AIR, "空心大厅必须保留 172800 格和完整空气目标");
            check(expected.minimum.equals(new BlockPos(40, 65, -140)) && expected.maximum.equals(new BlockPos(159, 82, -61)), "编译后的固定锚点或120×80×18范围改变");
            check(scene.toString().equals(authored), "编译不能把作者单图元覆盖成逐格模型");
            stage("compile", started, expected, "source_objects=1");

            // 居中大厅带负偏移，实际导出后逐条读回比对；每次读取结束就释放数据树，不并存两份大型导出副本。
            started = System.nanoTime();
            exportRoundTrip(directory.resolve("schematics"), blueprint, expected);
            stage("export", started, expected, "JSON_and_NBT_verified=true");

            // 蓝图 -> 真实建筑参数 -> BuildTool目标，随后释放蓝图和ops，项目持久化直接使用已解析的完整目标。
            started = System.nanoTime();
            JsonObject build = BuildingSceneAdapter.buildArguments(blueprint, ANCHOR, new JsonObject());
            List<BuildTaskRecord.Target> targets = BuildTool.resolvedTargets(build.getAsJsonArray("ops"), true);
            check(summarizeTargets(targets).equals(expected), "施工参数转换丢格、丢空气或改变锚点");
            check(!build.get("replace_existing").getAsBoolean() && !build.get("broaden_material_families").getAsBoolean(), "调大预算不能放宽替换或换材质权限");
            build.remove("ops"); blueprint = null;
            stage("build_arguments", started, expected, "native_body_started=false");

            started = System.nanoTime(); previewData(targets, expected);
            stage("preview_data", started, expected, "published=false; loaded_world_and_rendering_not_tested=true");

            // 保存真实解析结果后释放原目标；再用新Store模拟重启恢复，避免为比对一直留着两份完整JSON。
            started = System.nanoTime();
            String projectId = new BuildProjectStore(new StateIdentity(WORLD, directory)).save(DIMENSION, build, targets);
            Path projectFile = directory.resolve("build-projects").resolve(WORLD).resolve(projectId + ".json");
            long projectBytes = Files.size(projectFile);
            check(projectBytes > 8L * 1024 * 1024 && projectBytes <= BuildingBudgets.current().maxProjectBytes(), "夹具必须真实跨过旧8MiB项目上限且在新预算内");
            targets = null; build = null;
            restoreProject(directory, projectId, expected);
            stage("persistence", started, expected, "project_bytes=" + projectBytes + "; project_id=" + projectId);
            stage("total", total, expected, "artifacts=" + directory);
        } finally {
            // 此任务仅有临时文件，没有真实游戏存档；恢复默认配置，便于主任务继续跑其他独立验收。
            BuildingBudgets.initialize(Files.createTempDirectory("large-building-defaults-"));
        }
        System.out.println("LargeBuildingBudgetTest: passed; no native construction or rendering was performed");
    }

    private static void exportRoundTrip(Path directory, JsonObject blueprint, Summary expected) throws Exception {
        String id = UUID.randomUUID().toString();
        Path json = BuildingSceneExport.write(directory, id, blueprint, "json");
        verifyJson(json, expected);
        Path nbt = BuildingSceneExport.write(directory, id, blueprint, "nbt");
        verifyNbt(nbt, expected);
        System.out.println("LargeBuildingBudgetTest export_files json_bytes=" + Files.size(json) + " nbt_bytes=" + Files.size(nbt));
    }
    private static void verifyJson(Path path, Summary expected) throws Exception {
        try (var input = Files.newBufferedReader(path)) {
            check(summarizeBlueprint(JsonParser.parseReader(input).getAsJsonObject()).equals(expected), "JSON导出改变完整目标");
        }
    }
    private static void verifyNbt(Path path, Summary expected) throws Exception {
        var nbt = NbtIo.readCompressed(path, NbtAccounter.create(BuildingBudgets.current().maxImportNbtBytes()));
        var size = nbt.getList("size", Tag.TAG_INT); int[] origin = nbt.getIntArray("maicraft_offset");
        check(size.size() == 3 && size.getInt(0) == 120 && size.getInt(1) == 18 && size.getInt(2) == 80
                && java.util.Arrays.equals(origin, new int[]{-60, 0, -40}), "NBT尺寸或原模型负偏移丢失");
        var palette = nbt.getList("palette", Tag.TAG_COMPOUND); var tally = new Tally();
        for (var value : nbt.getList("blocks", Tag.TAG_COMPOUND)) {
            var cell = (net.minecraft.nbt.CompoundTag) value; var at = cell.getList("pos", Tag.TAG_INT);
            var state = palette.getCompound(cell.getInt("state"));
            check(state.getCompound("Properties").isEmpty(), "无属性石墙导出产生了额外验收状态");
            tally.add(ANCHOR.x() + origin[0] + at.getInt(0), ANCHOR.y() + origin[1] + at.getInt(1),
                    ANCHOR.z() + origin[2] + at.getInt(2), state.getString("Name"));
        }
        check(tally.finish().equals(expected), "NBT导出丢格、过滤空气或改变锚点");
    }
    private static void previewData(List<BuildTaskRecord.Target> targets, Summary expected) throws Exception {
        var cells = new LinkedHashMap<BlockPos, BlockState>(); targets.forEach(target -> cells.put(target.pos(), target.desiredState()));
        var preview = PreviewSession.design("large-budget", DIMENSION, "120×80×18 空心大厅", cells); cells.clear();
        check(preview.designOnly() && !preview.confirm() && preview.decision() == PreviewSession.Decision.DESIGN_ONLY, "只读大预览错误授予施工许可");
        var tally = new Tally();
        preview.cells().forEach((at, state) -> tally.add(at.getX(), at.getY(), at.getZ(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        check(tally.finish().equals(expected) && preview.dimension().equals(DIMENSION), "预览数据受旧65k限制或丢失空气与坐标");
    }
    private static void restoreProject(Path directory, String id, Summary expected) throws Exception {
        JsonObject restored = new BuildProjectStore(new StateIdentity(WORLD, directory)).load(id, DIMENSION);
        check(restored.get("project_id").getAsString().equals(id) && !restored.has("ops"), "恢复未沿用冻结项目编号");
        check(summarizeTargets(BuildProjectTargets.decode(restored.getAsJsonArray("project_targets"))).equals(expected), "重启恢复丢失目标、材料、空气或固定锚点");
        check(!restored.get("replace_existing").getAsBoolean() && !restored.get("broaden_material_families").getAsBoolean(), "恢复放宽了原施工权限");
    }
    private static Summary summarizeBlueprint(JsonObject blueprint) throws Exception {
        var tally = new Tally();
        for (var value : blueprint.getAsJsonArray("blocks")) {
            var cell = value.getAsJsonObject(); var at = cell.getAsJsonArray("offset");
            check(!cell.has("properties") || cell.getAsJsonObject("properties").isEmpty(), "无属性石墙被改成额外状态约束");
            tally.add(ANCHOR.x() + at.get(0).getAsInt(), ANCHOR.y() + at.get(1).getAsInt(), ANCHOR.z() + at.get(2).getAsInt(), cell.get("block_id").getAsString());
        }
        return tally.finish();
    }
    private static Summary summarizeTargets(List<BuildTaskRecord.Target> targets) throws Exception {
        var tally = new Tally();
        for (var target : targets) {
            check(target.strictIdentity() && !target.itemPlace() && target.exactProperties().isEmpty()
                    && target.finalProperties() != null && target.finalProperties().isEmpty(), "冻结目标的精确材料或最终属性要求改变");
            check(target.item() == (target.desiredState().isAir() ? net.minecraft.world.item.Items.AIR : net.minecraft.world.item.Items.STONE), "目标携带错误建材");
            var at = target.pos(); tally.add(at.getX(), at.getY(), at.getZ(), BuiltInRegistries.BLOCK.getKey(target.block()).toString());
        }
        return tally.finish();
    }
    private record Summary(int cells, int air, BlockPos minimum, BlockPos maximum, String digest) {}
    // 用顺序摘要覆盖每一格，不额外生成另一份大几何；坐标、材料、数量、空气与边界均参与往返验收。
    private static final class Tally {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count, air, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Tally() throws Exception {}
        void add(int x, int y, int z, String id) {
            check(id.equals("minecraft:air") || id.equals("minecraft:stone"), "大建筑材质发生替换");
            count++; if (id.equals("minecraft:air")) air++;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            digest.update((x + "," + y + "," + z + ":" + id + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Summary finish() { return new Summary(count, air, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), HexFormat.of().formatHex(digest.digest())); }
    }
    private static void stage(String name, long started, Summary summary, String extra) {
        System.out.printf(java.util.Locale.ROOT, "LargeBuildingBudgetTest stage=%s elapsed_ms=%.3f targets=%d air=%d solids=%d %s%n",
                name, (System.nanoTime() - started) / 1_000_000.0, summary.cells, summary.air, summary.cells - summary.air, extra);
    }
    private static JsonObject json(String source) { return JsonParser.parseString(source).getAsJsonObject(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
