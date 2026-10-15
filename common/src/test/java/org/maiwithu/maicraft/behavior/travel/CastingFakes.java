// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlacementConfirmation;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 浇筑的离线现场替身：一张方块表外加原版流体反应的最简规则——岩浆源旁边或上面有水就成黑曜石、
 * 流动的岩浆成圆石；水往空格里流（只在模板范围内流，别淹了整张表）；舀走水源时流水跟着退。
 * 背包按物品数记：倒桶换成空桶、舀桶换成满桶；拿东西按脚本给。每个小动作一刻做完，写进与世界视图共用的那张表。
 */
final class CastingFakes implements ConstructionSeams.ReadsSite, ConstructionSeams.PlansPlacement,
        ConstructionSeams.Clicks, DigsBlocks, ItemNeeds, ConstructionSeams.HoldsItem, ConstructionSeams.Guards,
        ConstructionSeams.Ledger, BringsPlayerClose {

    static final String BUCKET = "minecraft:bucket";
    static final String WATER_BUCKET = "minecraft:water_bucket";
    static final String LAVA_BUCKET = "minecraft:lava_bucket";
    static final String COBBLESTONE = "minecraft:cobblestone";
    static final String OBSIDIAN = "minecraft:obsidian";

    final Map<BlockPos, BlockState> world;
    final Map<String, Integer> inventory = new HashMap<>();
    final List<ItemRequest> asked = new ArrayList<>();
    final List<BlockPos> placed = new ArrayList<>();
    final List<BlockPos> poured = new ArrayList<>();
    final List<BlockPos> scooped = new ArrayList<>();
    final List<BlockPos> dug = new ArrayList<>();
    final List<BlockPos> temporaries = new ArrayList<>();
    /** 水在哪些格里能流：模板范围加池面一圈，流出去就不管了。 */
    final Set<BlockPos> flowRegion = new HashSet<>();
    /** 附近有没有能舀的水：没有时要水桶按缺东西失败。 */
    boolean waterNearby = true;
    /** 这些格倒岩浆进去成圆石（旁边是流动的岩浆、或别的意外），用来演"没成黑曜石"。 */
    final Set<BlockPos> castsCobblestone = new HashSet<>();
    BlockPos feet = new BlockPos(0, 65, 0);

    CastingFakes(Map<BlockPos, BlockState> world) {
        this.world = world;
    }

    /** 直岸场景：岸上是石头、池里两格深的岩浆源、上面是空气；模板范围与池面都加载着。 */
    static Map<BlockPos, BlockState> straightShore(PortalCastingLayout layout, int poolDepth) {
        Map<BlockPos, BlockState> world = new HashMap<>();
        for (int across = -8; across <= 9; across++) {
            for (int behind = -8; behind <= 8; behind++) {
                for (int up = -4; up <= 6; up++) {
                    BlockPos at = layout.cell(across, up, behind);
                    BlockState state;
                    if (up > 0) {
                        state = Blocks.AIR.defaultBlockState();
                    } else if (behind >= 1 || up <= -poolDepth) {
                        state = Blocks.STONE.defaultBlockState();
                    } else {
                        state = Blocks.LAVA.defaultBlockState();
                    }
                    world.put(at, state);
                }
            }
        }
        return world;
    }

    /** 让水在模板范围与池面一圈里流。 */
    void allowFlowAround(PortalCastingLayout layout) {
        flowRegion.addAll(layout.footprint());
        for (int across = -2; across <= 3; across++) {
            for (int behind = -2; behind <= 0; behind++) {
                flowRegion.add(layout.cell(across, 0, behind));
                flowRegion.add(layout.cell(across, -1, behind));
            }
        }
    }

    void give(String itemId, int count) {
        inventory.merge(itemId, count, Integer::sum);
    }

    /** 背包的物品栈快照，给世界视图替身。 */
    List<BackpackStack> stacks() {
        List<BackpackStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            if (entry.getValue() <= 0) continue;
            boolean building = entry.getKey().equals(COBBLESTONE) || entry.getKey().equals("minecraft:dirt")
                    || entry.getKey().equals(OBSIDIAN) || entry.getKey().equals("minecraft:oak_planks");
            stacks.add(new BackpackStack(entry.getKey(), entry.getValue(), 64, false, false, false, building));
        }
        return stacks;
    }

    ConstructionServices services() {
        return new ConstructionServices(this, this, this, this, this, this, this, this, this, null);
    }

    BlockState state(BlockPos pos, BlockState fallback) {
        return world.getOrDefault(pos, fallback);
    }

    @Override public boolean loaded(BlockPos pos) { return true; }
    @Override public BlockState state(BlockPos pos) { return world.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    @Override public String dimension() { return "minecraft:overworld"; }
    @Override public BlockPos feet() { return feet; }
    @Override public int carried(String itemId) { return inventory.getOrDefault(itemId, 0); }
    @Override public boolean creative() { return false; }
    @Override public boolean unbreakable(BlockPos pos) { return false; }
    @Override public Optional<String> temporaryMaterial() { return Optional.of(COBBLESTONE); }

    @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
        return Optional.of(new PlacementPrediction.Placement(cell.pos(), Direction.UP, cell.state()));
    }

    @Override public boolean requiresSneak(BlockPos clicked) { return false; }

    @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
            PlacementConfirmation confirmation) {
        return click(() -> {
            String item = BuiltInRegistries.ITEM.getKey(cell.item()).toString();
            if (carried(item) <= 0) return;
            inventory.merge(item, -1, Integer::sum);
            world.put(cell.pos(), placement.predicted());
            placed.add(cell.pos());
            settle();
        });
    }

    @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
        return click(() -> {
            String item = BuiltInRegistries.ITEM.getKey(cell.item()).toString();
            if (carried(item) <= 0) return;
            inventory.merge(item, -1, Integer::sum);
            inventory.merge(BUCKET, 1, Integer::sum);
            poured.add(cell.pos());
            boolean lava = cell.state().getFluidState().getType().isSame(Fluids.LAVA);
            if (lava && castsCobblestone.contains(cell.pos())) {
                world.put(cell.pos(), Blocks.COBBLESTONE.defaultBlockState());
            } else {
                world.put(cell.pos(), cell.state());
            }
            settle();
        });
    }

    @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
        return click(() -> {
            BlockState state = state(source);
            if (!state.getFluidState().isSource() || carried(BUCKET) <= 0) return;
            scooped.add(source);
            inventory.merge(BUCKET, -1, Integer::sum);
            boolean water = state.getFluidState().getType().isSame(Fluids.WATER);
            inventory.merge(water ? WATER_BUCKET : LAVA_BUCKET, 1, Integer::sum);
            world.put(source, Blocks.AIR.defaultBlockState());
            if (water) drainFlowingWater();
            settle();
        });
    }

    @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
        return click(() -> { });
    }

    private Click click(Runnable effect) {
        return new Click(immediate(effect), () -> InteractionResult.applied("替身确认"));
    }

    @Override public Optional<Action> dig(BlockPos target) {
        return Optional.of(immediate(() -> {
            dug.add(target);
            world.put(target, Blocks.AIR.defaultBlockState());
            settle();
        }));
    }

    @Override public Action actionFor(ItemRequest request, Permissions permissions) {
        asked.add(request);
        String item = request.wanted().itemId();
        if (WATER_BUCKET.equals(item)) {
            if (!waterNearby) {
                return failing(Problem.of(Problem.Kind.NEED_ITEM, "48 格内没有舀了会自己补回来的水", null));
            }
            return immediate(() -> {
                if (carried(BUCKET) > 0) {
                    inventory.merge(BUCKET, -1, Integer::sum);
                    inventory.merge(WATER_BUCKET, 1, Integer::sum);
                }
            });
        }
        return immediate(() -> inventory.merge(item, request.count(), Integer::sum));
    }

    @Override public ConstructionSeams.HoldPlan hold(String itemId) {
        return carried(itemId) > 0 ? new ConstructionSeams.HoldPlan.Ready() : new ConstructionSeams.HoldPlan.NotCarried();
    }

    @Override public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
        return Optional.empty();
    }

    @Override public void siteStarted(Blueprint blueprint) { }
    @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) { temporaries.add(pos); }
    @Override public void temporaryRemoved(BlockPos pos) { temporaries.remove(pos); }
    @Override public List<BlockPos> temporaries() { return List.copyOf(temporaries); }

    @Override public Action toward(ApproachTarget target, Permissions permissions) {
        return immediate(() -> { });
    }

    /** 原版流体的最简结算：水往模板范围里的空格流；岩浆源贴水成黑曜石，流动的岩浆成圆石。 */
    void settle() {
        for (int round = 0; round < 12; round++) {
            boolean changed = false;
            List<BlockPos> waters = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> entry : world.entrySet()) {
                if (entry.getValue().getFluidState().getType().isSame(Fluids.WATER)) waters.add(entry.getKey());
            }
            for (BlockPos water : waters) {
                for (Direction side : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos next = water.relative(side);
                    if (!flowRegion.contains(next) || !state(next).isAir()) continue;
                    world.put(next, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
                    changed = true;
                }
            }
            List<BlockPos> lavas = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> entry : world.entrySet()) {
                if (entry.getValue().getFluidState().getType().isSame(Fluids.LAVA)) lavas.add(entry.getKey());
            }
            for (BlockPos lava : lavas) {
                boolean wet = false;
                for (Direction side : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    if (state(lava.relative(side)).getFluidState().getType().isSame(Fluids.WATER)) wet = true;
                }
                if (!wet) continue;
                world.put(lava, state(lava).getFluidState().isSource()
                        ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                changed = true;
            }
            if (!changed) return;
        }
    }

    /** 水源舀走后流水退净：原版流水没有源会消失。 */
    private void drainFlowingWater() {
        List<BlockPos> flowing = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : world.entrySet()) {
            var fluid = entry.getValue().getFluidState();
            if (fluid.getType().isSame(Fluids.WATER) && !fluid.isSource()) flowing.add(entry.getKey());
        }
        for (BlockPos at : flowing) world.put(at, Blocks.AIR.defaultBlockState());
    }

    static Action immediate(Runnable effect) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                effect.run();
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "替身动作";
            }
        };
    }

    static Action failing(Problem problem) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(problem);
            }

            @Override public String describe() {
                return "替身失败";
            }
        };
    }
}
