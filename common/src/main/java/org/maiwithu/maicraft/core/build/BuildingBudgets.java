// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** 建筑按整份设计保留坐标与空气要求；资源预算在客户端启动时读取，不因每帧查询反复访问磁盘。 */
public final class BuildingBudgets {
    public static final String CONFIG_PATH = "config/maicraft-building.properties";
    private static volatile BuildingBudgets active = defaults();
    private final Map<Key, Number> values;
    private final List<Diagnostic> diagnostics;

    public record Diagnostic(String key, String suppliedValue, String message) {}

    // 总工程输入、实际导入和可见预览分别计费；默认目标数可覆盖一个120×80×18格的完整大厅。
    enum Key {
        TARGETS("maxTargets", 262_144, Integer.MAX_VALUE, "建筑最终目标格上限，包含明确的空气目标"),
        OBJECTS("maxObjects", 8_192, Integer.MAX_VALUE, "作者对象、组件和展开节点预算"),
        CONNECTIONS("maxConnections", 16_384, Integer.MAX_VALUE, "布尔切割引用等模型连接预算"),
        RADIUS("maxRadius", 512, (Integer.MAX_VALUE - 1) / 2, "模型相对锚点的最大坐标半径，单位：格"),
        VOXEL_WORK("maxVoxelWork", 67_108_864L, Long.MAX_VALUE, "建模采样与面比较的总工作量预算"),
        SCENE_BYTES("maxSceneBytes", 8L << 20, Integer.MAX_VALUE - 1L, "可编辑作者模型文件上限，单位：字节"),
        PROJECT_BYTES("maxProjectBytes", 128L << 20, Integer.MAX_VALUE - 1L, "冻结施工单文件上限，单位：字节"),
        SCAFFOLDS("maxScaffolds", 65_536, Integer.MAX_VALUE, "整个工程可记录的临时支撑数，不表示一次会搭建这么多支撑"),
        ACCESS_CELLS("maxCleanupAccessCells", 524_288, Integer.MAX_VALUE, "清理脚手架时可分帧观察的通行范围格数，未知区块仍不允许施工"),
        SCAFFOLD_BYTES("maxScaffoldBytes", 8L << 20, Integer.MAX_VALUE - 1L, "原生确认的临时支撑账上限，单位：字节"),
        MCP_BYTES("maxMcpRequestBytes", 64L << 20, Integer.MAX_VALUE - 1L, "本地MCP请求字节上限，至少1024；也影响其他能力请求"),
        STATE_BYTES("maxIntentStateBytes", 256L << 20, Integer.MAX_VALUE - 1L, "当前世界的语义任务记录字节上限，也影响其他能力的任务保存"),
        IMPORT_FILE_BYTES("maxImportFileBytes", 64L << 20, Integer.MAX_VALUE - 1L, "导入蓝图源文件上限，单位：字节"),
        IMPORT_NBT_BYTES("maxImportNbtBytes", 256L << 20, Long.MAX_VALUE, "NBT解压后的原生读取预算，单位：字节"),
        IMPORT_VOLUME("maxImportVolume", 67_108_864L, Long.MAX_VALUE, "导入区域需要遍历的总格数，包含原格式的空气"),
        IMPORT_PALETTE("maxImportPaletteEntries", 65_536, Integer.MAX_VALUE, "导入材质状态表的条目预算"),
        IMPORT_SLICE("importSliceMillis", 2.0, "每次游戏更新展开导入蓝图的时间预算，单位：毫秒"),
        IMPORT_ENTRIES("importEntriesPerSlice", 512, Integer.MAX_VALUE, "每次游戏更新最多展开的导入条目"),
        PREVIEW_CELLS("preview.maxCells", 262_144, Integer.MAX_VALUE, "单份预览的方块和部件总预算"),
        PREVIEW_DISTANCE("preview.distance", 256, Integer.MAX_VALUE, "预览绘制距离，单位：格；不会强制加载远处区块"),
        PREVIEW_FRAME("preview.frameMillis", 2.0, "每帧准备和刷新预览的时间预算，单位：毫秒"),
        PREVIEW_PREPARATION("preview.preparationSteps", 4_096, Integer.MAX_VALUE, "每帧最多处理的预览准备步骤"),
        PREVIEW_REBUILD("preview.rebuildCells", 512, Integer.MAX_VALUE, "每帧重建预览网格的格数预算"),
        PREVIEW_RESORT("preview.resortSections", 8, Integer.MAX_VALUE, "每帧最多重排的透明预览分区数");

        final String property, description;
        final Number fallback;
        final long maximum;
        final boolean millis;

        Key(String property, long fallback, long maximum, String description) {
            this.property = property; this.fallback = fallback; this.maximum = maximum;
            this.description = description; this.millis = false;
        }
        Key(String property, double fallback, String description) {
            this.property = property; this.fallback = fallback; this.maximum = Long.MAX_VALUE / 1_000_000;
            this.description = description; this.millis = true;
        }
    }

    private BuildingBudgets(Map<Key, Number> values, List<Diagnostic> diagnostics) {
        this.values = Map.copyOf(values); this.diagnostics = List.copyOf(diagnostics);
    }

    public static BuildingBudgets current() { return active; }
    public static BuildingBudgets defaults() { return fromProperties(new Properties()); }

    // 客户端登记建造工具前安装同一份启动快照；仅改文件不会改变已经运行的施工预算。
    public static synchronized BuildingBudgets initialize(Path gameDirectory) {
        active = BuildingBudgetFile.load(gameDirectory.resolve(CONFIG_PATH));
        return active;
    }

    public static BuildingBudgets fromProperties(Properties properties) {
        var values = new EnumMap<Key, Number>(Key.class); var issues = new ArrayList<Diagnostic>();
        for (Key key : Key.values()) {
            String raw = properties == null ? null : properties.getProperty(key.property);
            Number value = key.fallback;
            if (raw != null) {
                try {
                    if (key.millis) {
                        double parsed = Double.parseDouble(raw.strip());
                        if (!Double.isFinite(parsed) || parsed < .000001 || parsed > key.maximum) throw new NumberFormatException();
                        value = parsed;
                    } else {
                        long parsed = Long.parseLong(raw.strip());
                        if (parsed < (key == Key.MCP_BYTES ? 1024 : 1) || parsed > key.maximum) throw new NumberFormatException();
                        value = parsed;
                    }
                } catch (NumberFormatException invalid) {
                    // 单项写错只退回这一项的默认值，保留其他场景预算；不把非法上限解释成无限制。
                    issues.add(new Diagnostic(key.property, raw, "无效建筑预算，已使用默认值 " + key.fallback
                            + "；需要" + (key.millis ? "正有限毫秒数" : "不小于 " + (key == Key.MCP_BYTES ? 1024 : 1) + " 的整数")
                            + "且不超过 " + key.maximum));
                }
            }
            values.put(key, value);
        }
        return new BuildingBudgets(values, issues);
    }

    BuildingBudgets withDiagnostic(Diagnostic diagnostic) {
        var all = new ArrayList<>(diagnostics); all.add(diagnostic); return new BuildingBudgets(values, all);
    }
    public List<Diagnostic> diagnostics() { return diagnostics; }

    // 各调用方读取同一组有单位的有效值，避免模型已允许的大建筑又被预览、导入或恢复中的旧常量截断。
    public int maxTargets() { return values.get(Key.TARGETS).intValue(); }
    public int maxObjects() { return values.get(Key.OBJECTS).intValue(); }
    public int maxConnections() { return values.get(Key.CONNECTIONS).intValue(); }
    public int maxRadius() { return values.get(Key.RADIUS).intValue(); }
    public long maxVoxelWork() { return values.get(Key.VOXEL_WORK).longValue(); }
    public int maxSceneBytes() { return values.get(Key.SCENE_BYTES).intValue(); }
    public int maxProjectBytes() { return values.get(Key.PROJECT_BYTES).intValue(); }
    public int maxScaffolds() { return values.get(Key.SCAFFOLDS).intValue(); }
    public int maxCleanupAccessCells() { return values.get(Key.ACCESS_CELLS).intValue(); }
    public int maxScaffoldBytes() { return values.get(Key.SCAFFOLD_BYTES).intValue(); }
    public int maxMcpRequestBytes() { return values.get(Key.MCP_BYTES).intValue(); }
    public int maxIntentStateBytes() { return values.get(Key.STATE_BYTES).intValue(); }
    public int maxImportFileBytes() { return values.get(Key.IMPORT_FILE_BYTES).intValue(); }
    public long maxImportNbtBytes() { return values.get(Key.IMPORT_NBT_BYTES).longValue(); }
    public long maxImportVolume() { return values.get(Key.IMPORT_VOLUME).longValue(); }
    public int maxImportPaletteEntries() { return values.get(Key.IMPORT_PALETTE).intValue(); }
    public double importSliceMillis() { return values.get(Key.IMPORT_SLICE).doubleValue(); }
    public int importEntriesPerSlice() { return values.get(Key.IMPORT_ENTRIES).intValue(); }
    public int maxPreviewCells() { return values.get(Key.PREVIEW_CELLS).intValue(); }
    public int previewDistance() { return values.get(Key.PREVIEW_DISTANCE).intValue(); }
    public double previewFrameMillis() { return values.get(Key.PREVIEW_FRAME).doubleValue(); }
    public int previewPreparationSteps() { return values.get(Key.PREVIEW_PREPARATION).intValue(); }
    public int previewRebuildCells() { return values.get(Key.PREVIEW_REBUILD).intValue(); }
    public int previewResortSections() { return values.get(Key.PREVIEW_RESORT).intValue(); }
}
