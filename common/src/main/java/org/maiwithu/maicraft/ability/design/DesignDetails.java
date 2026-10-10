// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.ability.design.api.CompiledDesign;
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
        List<Integer> offset) implements ResultDetails {

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
                new Overlaps(compiled.overlapCells(), compiled.overlapEvents(), examples), null, null, null);
    }

    DesignDetails withInspection(JsonObject text) {
        return new DesignDetails(designId, parentDesignId, objectCount, expandedCount, cellCount, materials, bounds, overlaps, text, file, offset);
    }

    DesignDetails withFile(String path, List<Integer> shift) {
        return new DesignDetails(designId, parentDesignId, objectCount, expandedCount, cellCount, materials, bounds, overlaps, inspection, path, shift);
    }
}
