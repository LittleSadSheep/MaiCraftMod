// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;

/**
 * 每格的放法：按登记的放置规则逐个问，取第一个非空的答案；都不答就照原版默认（方块自己的物品、对着目标格放、不用等别的格）。
 * 规则表由启动从联动登记表拿来经构造传进来，这里不存可变的共享状态。
 */
public final class CellPlacements {

    /** 什么都没加载的读口：算材料清单时没有现场，规则只能看物品。 */
    public static final ReadsBlocks NO_WORLD = new ReadsBlocks() {
        @Override public boolean loaded(BlockPos pos) {
            return false;
        }

        @Override public BlockState state(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }
    };

    private static final CellPlacements NONE = new CellPlacements(List.of());

    private final List<PlacementRules> rules;

    private CellPlacements(List<PlacementRules> rules) {
        this.rules = List.copyOf(rules);
    }

    public static CellPlacements of(List<PlacementRules> rules) {
        return rules == null || rules.isEmpty() ? NONE : new CellPlacements(rules);
    }

    /** 没有联动模组的放法：一律按原版默认。 */
    public static CellPlacements none() {
        return NONE;
    }

    /** 放方块的格换成规则说的物品（带漏斗拿漏斗放）；不归规则管的、清空与倒桶的格原样返回。 */
    public PlannedCell withItem(PlannedCell cell) {
        if (cell.kind() != CellKind.BLOCK) return cell;
        return placement(cell.state(), cell.pos(), NO_WORLD)
                .map(answer -> answer.item() == cell.item() ? cell : cell.withItem(answer.item()))
                .orElse(cell);
    }

    /** 一组格逐个换成规则说的物品，算材料清单与开工前用。 */
    public List<PlannedCell> withItems(List<PlannedCell> cells) {
        if (rules.isEmpty()) return cells;
        List<PlannedCell> out = new ArrayList<>(cells.size());
        for (PlannedCell cell : cells) out.add(withItem(cell));
        return out;
    }

    /** 这一格对着哪一格放：规则按现场说的那一格，不归规则管时就是它自己。 */
    public BlockPos placeAt(PlannedCell cell, ReadsBlocks world) {
        return placement(cell.state(), cell.pos(), world).map(PlacementRules.Placement::placeAt).orElse(cell.pos());
    }

    /** 这一格要等哪些格先放好（绝对坐标）；不用等为空表。 */
    public List<BlockPos> waitsFor(PlannedCell cell) {
        if (cell.kind() != CellKind.BLOCK) return List.of();
        for (PlacementRules rule : rules) {
            List<BlockPos> offsets = rule.placeAfter(cell.state());
            if (offsets == null || offsets.isEmpty()) continue;
            List<BlockPos> out = new ArrayList<>(offsets.size());
            for (BlockPos offset : offsets) out.add(cell.pos().offset(offset));
            return out;
        }
        return List.of();
    }

    /** 要不要等别的格：施工顺序把它当贴附件，排在同层骨架之后。 */
    public boolean waits(PlannedCell cell) {
        return !waitsFor(cell).isEmpty();
    }

    /** 物品放下这一下以后自己再改成的样子（竖直齿轮箱定轴向）；第一份改了的规则算数，都不改原样返回。 */
    public BlockState afterPlacing(Item item, BlockState placed, ClientLevel level, BlockPos at, Direction playerFacing) {
        if (placed == null) return null;
        for (PlacementRules rule : rules) {
            BlockState settled = rule.afterPlacing(item, placed, level, at, playerFacing);
            if (settled != null && !settled.equals(placed)) return settled;
        }
        return placed;
    }

    private Optional<PlacementRules.Placement> placement(BlockState state, BlockPos at, ReadsBlocks world) {
        for (PlacementRules rule : rules) {
            Optional<PlacementRules.Placement> answer = rule.placementFor(state, at, world);
            if (answer != null && answer.isPresent()) return answer;
        }
        return Optional.empty();
    }
}
