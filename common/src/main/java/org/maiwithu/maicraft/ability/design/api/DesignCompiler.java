// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.ability.design.DesignSampling;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;
import org.maiwithu.maicraft.ability.design.StateTransforms;
import org.maiwithu.maicraft.behavior.construction.BlockStateRules;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 设计编译：图纸 → 格式校验 → 组件展开 → 逐格采样与涂装 → 叠加结算 → 计划格。
 * 输出相对设计原点的计划格，材料里作者点名的属性就是验收标准；作者写的属性若会被施工的归一规则改掉，
 * 在这里就拒绝，不让图纸和验收互相打架。
 */
public final class DesignCompiler {

    private DesignCompiler() {}

    /** 只校验与展开，不逐格采样；改图时用来快速发现错误。 */
    public static void validate(JsonObject drawing) {
        DesignSampling.plan(drawing);
    }

    /** 编译整张图纸。 */
    public static CompiledDesign compile(JsonObject drawing) {
        var sampled = DesignSampling.sample(drawing);
        List<PlannedCell> cells = new ArrayList<>(sampled.cells().size());
        for (var entry : sampled.cells().entrySet()) cells.add(cell(entry.getKey(), entry.getValue()));
        List<CompiledDesign.Overlap> overlaps = sampled.overlapExamples().stream()
                .map(overlap -> new CompiledDesign.Overlap(pos(overlap.offset()), overlap.previousObject(), overlap.incomingObject(),
                        overlap.previousState().get("block_id").getAsString(), overlap.incomingState().get("block_id").getAsString()))
                .toList();
        var model = sampled.plan().model();
        return new CompiledDesign(cells, drawing.getAsJsonArray("objects").size(),
                drawing.has("components") ? drawing.getAsJsonObject("components").size() : 0,
                model.expandedCount(), model.cutterCount(), sampled.plan().work(),
                sampled.overlapCells(), sampled.overlapEvents(), overlaps);
    }

    /** 一格的稀疏状态变成计划格：空气是清空格，水源与岩浆源是倒桶格，其余是放方块。 */
    private static PlannedCell cell(Point at, JsonObject state) {
        BlockPos pos = pos(at);
        if (state.get("block_id").getAsString().equals("minecraft:air")) return PlannedCell.air(pos);
        BlockState resolved = StateTransforms.resolve(state);
        Set<String> required = state.has("properties") ? state.getAsJsonObject("properties").keySet() : Set.of();
        BlockState normalized = BlockStateRules.normalize(resolved);
        for (String name : required) {
            Property<?> property = resolved.getBlock().getStateDefinition().getProperty(name);
            if (property != null && normalized.hasProperty(property) && !normalized.getValue(property).equals(resolved.getValue(property))) {
                throw new IllegalArgumentException("材料 " + state.get("block_id").getAsString() + " 的属性 " + name
                        + " 是运行态，施工时会被归一改掉；图纸里别点名它");
            }
        }
        return PlannedCell.block(pos, resolved, required);
    }

    private static BlockPos pos(Point at) {
        return new BlockPos(at.x(), at.y(), at.z());
    }
}
