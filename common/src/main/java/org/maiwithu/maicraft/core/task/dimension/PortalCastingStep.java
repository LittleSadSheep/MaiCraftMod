// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;

/** 模具与浇筑顺序在接单时固定；每一步使用实时方块、背包和原生回执推进。 */
record PortalCastingStep(Kind kind, BlockPos target) {
    enum Kind { CLEAR, BUILD, POUR_WATER, TAKE_WATER, CAST, DRAIN }
    static final List<Item> SUPPORTS = List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.DIRT);
    private static final List<Item> PICKS = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    static List<PortalCastingStep> plan(NetherPortalCastingLayout layout) {
        var steps = new ArrayList<PortalCastingStep>();
        // 先清理门框上方与后方的声明格；不为“等水退去”阻止本来就能执行的拆块。
        layout.upperFrame().stream().filter(p -> p.getY() > layout.origin().getY())
                .forEach(p -> steps.add(new PortalCastingStep(Kind.CLEAR, p)));
        layout.clearance().forEach(p -> steps.add(new PortalCastingStep(Kind.CLEAR, p)));
        steps.add(new PortalCastingStep(Kind.BUILD, layout.placeholder()));
        steps.add(new PortalCastingStep(Kind.POUR_WATER, layout.initialWater()));
        steps.add(new PortalCastingStep(Kind.CLEAR, layout.placeholder()));
        layout.bottom().forEach(p -> steps.add(new PortalCastingStep(Kind.CAST, p)));
        steps.add(new PortalCastingStep(Kind.TAKE_WATER, layout.initialWater()));
        layout.mold().forEach(p -> steps.add(new PortalCastingStep(Kind.BUILD, p)));
        steps.add(new PortalCastingStep(Kind.POUR_WATER, layout.castingWater()));
        layout.upperFrame().forEach(p -> steps.add(new PortalCastingStep(Kind.CAST, p)));
        steps.add(new PortalCastingStep(Kind.TAKE_WATER, layout.castingWater()));
        steps.add(new PortalCastingStep(Kind.DRAIN, layout.origin()));
        return List.copyOf(steps);
    }

    /** 单桶先装水，之后水留在世界内时才循环装岩浆；不要求先挖到黑曜石或钻石。 */
    static PortalPreparationSupplies.Need supplies(LocalPlayer player, boolean initial) {
        if (initial && PlayerInv.count(player.getInventory(), Items.BUCKET) + PlayerInv.count(player.getInventory(), Items.WATER_BUCKET) == 0)
            return new PortalPreparationSupplies.Need(List.of(Items.BUCKET), 1, "single casting bucket");
        if (initial && PICKS.stream().noneMatch(item -> PlayerInv.count(player.getInventory(), item) > 0))
            return new PortalPreparationSupplies.Need(List.of(Items.STONE_PICKAXE), 1, "excavate shallow pool bottom");
        if (SUPPORTS.stream().mapToInt(item -> PlayerInv.count(player.getInventory(), item)).sum() < (initial ? 7 : 1))
            return new PortalPreparationSupplies.Need(SUPPORTS, initial ? 7 : 1, "temporary portal mold");
        return null;
    }

    static BuildTaskRecord build(LocalPlayer player, String id, long deadline, BlockPos target,
                                 NetherPortalCastingLayout layout, PortalPreparationPolicy policy) {
        Item material = SUPPORTS.stream().filter(item -> PlayerInv.count(player.getInventory(), item) > 0).findFirst().orElseThrow();
        var block = Block.byItem(material);
        var record = new BuildTaskRecord(id, deadline, List.of(new BuildTaskRecord.Target(block, material, target,
                "浇筑地狱门的临时模具", null, null, null)), true);
        record.automaticMachineModification(Set.of(target));
        record.materialSupplyProtection(List.copyOf(layout.footprint()));
        record.toolSupply(new BuildTaskRecord.ToolSupply(policy.materialPolicy(), policy.sources(true),
                policy.allowCombat(), policy.protectedLabels()));
        return record;
    }

    /** 清掉指定旧格时按真实工具效率选主手；只破坏这一格，不因水立刻流入就继续左键。 */
    static InteractAtTaskRecord clear(LocalPlayer player, String id, long deadline, BlockPos target, BlockState actual) {
        Item tool = player.getInventory().items.stream().filter(stack -> !stack.isEmpty())
                .max(Comparator.comparingDouble(stack -> stack.getDestroySpeed(actual)))
                .map(stack -> stack.getItem()).orElse(null);
        return new InteractAtTaskRecord(id, deadline, MouseButton.LEFT, target, 0, tool, null, actual.getBlock()).withApproach(false);
    }
}
