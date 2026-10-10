// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * 计划格：一格该变成什么、用什么放、验收看哪些属性、由谁来放。
 *
 * <p>位置在相对蓝图里是相对设计原点的偏移，落到锚点后是绝对坐标。状态构造时先经方块状态规则归一，
 * 作者点名的属性（{@code required}）才是验收标准，其余按原生落法接受。
 *
 * @param pos      这一格的位置
 * @param kind     放方块、清空还是倒桶
 * @param state    要求的方块状态（清空时是空气，倒桶时是水或岩浆）
 * @param item     放它要用的物品：方块自己的物品、泥土（耕地与土径）或桶；清空时为空气
 * @param required 作者点名的属性名，验收只比这些
 * @param placedBy 施工引擎放，还是留给机器
 */
public record PlannedCell(BlockPos pos, CellKind kind, BlockState state, Item item, Set<String> required,
                          PlacedBy placedBy) {

    public PlannedCell {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        kind = Objects.requireNonNull(kind, "kind");
        state = Objects.requireNonNull(state, "state");
        item = Objects.requireNonNull(item, "item");
        required = required == null ? Set.of() : Set.copyOf(required);
        placedBy = placedBy == null ? PlacedBy.BUILDER : placedBy;
        for (String name : required) {
            if (state.getBlock().getStateDefinition().getProperty(name) == null) {
                throw new IllegalArgumentException(state.getBlock().getName().getString() + " 没有属性 " + name);
            }
        }
        if (kind == CellKind.AIR && !state.isAir()) throw new IllegalArgumentException("清空的格状态必须是空气");
        if (kind == CellKind.FLUID_SOURCE && state.getFluidState().isEmpty())
            throw new IllegalArgumentException("倒桶的格状态必须是水源或岩浆源");
    }

    /** 清空这一格。 */
    public static PlannedCell air(BlockPos pos) {
        return new PlannedCell(pos, CellKind.AIR, Blocks.AIR.defaultBlockState(), Items.AIR, Set.of(), PlacedBy.BUILDER);
    }

    /** 放一个方块；物品按方块状态规则取（耕地按泥土等），没点名属性时按原生落法验收。 */
    public static PlannedCell block(BlockPos pos, BlockState state, Set<String> required) {
        BlockState normalized = BlockStateRules.normalize(state);
        if (normalized.isAir()) return air(pos);
        if (!normalized.getFluidState().isEmpty() && normalized.getFluidState().isSource()
                && normalized.getBlock().defaultBlockState().getFluidState().isSource()) {
            return fluid(pos, normalized);
        }
        return new PlannedCell(pos, CellKind.BLOCK, normalized, BlockStateRules.materialItem(normalized.getBlock()),
                required, PlacedBy.BUILDER);
    }

    /** 倒一桶：水源用水桶，岩浆源用岩浆桶。 */
    public static PlannedCell fluid(BlockPos pos, BlockState source) {
        Item bucket = source.getFluidState().getType() == Fluids.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        return new PlannedCell(pos, CellKind.FLUID_SOURCE, source, bucket, Set.of(), PlacedBy.BUILDER);
    }

    /** 同一格交给机器放。 */
    public PlannedCell byMachine() {
        return new PlannedCell(pos, kind, state, item, required, PlacedBy.MACHINE);
    }

    /** 从空位建成这一格要几件物品：门上半、床头不计费，双层半砖两件，雪层按层数。 */
    public int materialCount() {
        return kind == CellKind.BLOCK ? BlockStateRules.materialCount(state) : kind == CellKind.FLUID_SOURCE ? 1 : 0;
    }

    /** 蓝图落到锚点：偏移绕原点转过去再加锚点，有朝向的方块状态一起转。 */
    PlannedCell placedAt(BlockPos anchor, Rotation turn) {
        BlockPos turned = switch (turn) {
            case NONE -> pos;
            case CLOCKWISE_90 -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            case CLOCKWISE_180 -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
        };
        BlockState turnedState = turn == Rotation.NONE ? state : state.rotate(turn);
        return new PlannedCell(anchor.offset(turned), kind, turnedState, item, required, placedBy);
    }
}
