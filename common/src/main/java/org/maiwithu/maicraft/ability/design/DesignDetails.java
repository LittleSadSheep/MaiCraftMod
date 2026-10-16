// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.ability.design.api.CompiledDesign;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.BuildingLayout;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 设计能力的结果细节：编号、父版本、统计（对象数、展开数、格数、每种材料几件、包围盒、叠加冲突）、
 * 看图的正文、导出的文件与平移量。没有的字段不写。
 *
 * @param designId       设计编号
 * @param parentDesignId 改自哪一版；新建为 null
 * @param objectCount    作者写的对象数
 * @param expandedCount  展开后的对象数
 * @param cellCount      格数
 * @param materials      每种材料几件
 * @param bounds         相对设计原点的包围盒
 * @param overlaps       叠加冲突
 * @param inspection     看图的正文（inspect 才有）
 * @param file           导出的文件（export 才有）
 * @param offset         nbt 平移掉的量，再导入时加回去（export nbt 才有）
 * @param anchor         读现场：照这一版补建时落在哪一格（设计原点）
 * @param unseen         读现场：范围里有东西却没看到、没加载的格数
 * @param site           读现场：读出来的那张层图（图例与每一层），整份给
 * @param layout         这一栋的布局：读现场按现场算，看图按图纸算
 * @param check          读现场指着一栋建筑档案时：档案的整栋蓝图对现场比
 * @param archive        读现场用到或新建的建筑档案名
 */
public record DesignDetails(
        String designId,
        String parentDesignId,
        Integer objectCount,
        Integer expandedCount,
        Integer cellCount,
        Map<String, Integer> materials,
        Bounds bounds,
        Overlaps overlaps,
        JsonObject inspection,
        String file,
        List<Integer> offset,
        WorldPosition anchor,
        Integer unseen,
        JsonObject site,
        BuildingLayout layout,
        BlueprintCheck.Report check,
        String archive) implements ResultDetails {

    /** 包围盒的两个角。 */
    public record Bounds(List<Integer> min, List<Integer> max) {}

    /** 叠加冲突：几格、几次、前几个例子。 */
    public record Overlaps(int cells, long events, List<String> examples) {}

    /** 编译结果的统计部分。 */
    static DesignDetails of(String id, String parentId, CompiledDesign compiled) {
        var bounds = compiled.bounds();
        List<String> examples = compiled.overlapExamples().stream()
                .map(overlap -> overlap.offset().toShortString() + "：" + overlap.previousObject() + " 的 " + overlap.previousBlock()
                        + " 被 " + overlap.incomingObject() + " 的 " + overlap.incomingBlock() + " 盖掉")
                .toList();
        return new DesignDetails(id, parentId, compiled.objectCount(), compiled.expandedCount(), compiled.cellCount(),
                compiled.materials(),
                new Bounds(List.of(bounds.min().getX(), bounds.min().getY(), bounds.min().getZ()),
                        List.of(bounds.max().getX(), bounds.max().getY(), bounds.max().getZ())),
                new Overlaps(compiled.overlapCells(), compiled.overlapEvents(), examples), null, null, null,
                null, null, null, null, null, null);
    }

    /** 只有看图正文（与布局）的那种：inspect 用。 */
    static DesignDetails inspection(String id, String parentId, JsonObject text, BuildingLayout layout) {
        return new DesignDetails(id, parentId, null, null, null, null, null, null, text, null, null,
                null, null, null, layout, null, null);
    }

    DesignDetails withFile(String path, List<Integer> shift) {
        return new DesignDetails(designId, parentDesignId, objectCount, expandedCount, cellCount, materials, bounds, overlaps, inspection, path, shift,
                anchor, unseen, site, layout, check, archive);
    }

    /** 读现场的那几项：落在哪、没看到几格、读出来的层图、布局、整栋比对、档案名。 */
    DesignDetails withSite(WorldPosition at, int hidden, JsonObject drawn, BuildingLayout seen, BlueprintCheck.Report compared, String archiveName) {
        return new DesignDetails(designId, parentDesignId, objectCount, expandedCount, cellCount, materials, bounds, overlaps, inspection, file, offset,
                at, hidden, drawn, seen, compared, archiveName);
    }
}
