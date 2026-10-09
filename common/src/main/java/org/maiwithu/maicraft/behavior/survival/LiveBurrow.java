// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.StepwiseActions;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖三填一的生产实现：读脚下一列与坑壁，挖用原生挖掘，封口换一块普通方块对着坑壁原生放下，
 * 爬回地面用走到（只垫不挖，垫的就是挖坑时捡到的方块）。
 *
 * <p>脚下要挖的格子按保护判断看是不是别人的东西；当刻没有世界（还没进世界）时一律按受保护处理，
 * 不挖。封口只用身上的普通方块：会掉落的（沙子、砂砾）、带方块实体的（箱子这类）、贵重的都不用。
 */
public final class LiveBurrow implements BurrowInTask.Moves {

    private final Supplier<PlayerContext> contexts;
    private final Supplier<BlockBreaking> diggings;
    private final Interactions interactions;
    private final MovesToMainhand toMainhand;
    private final WalkTo walks;
    private final Supplier<Protection> protection;

    public LiveBurrow(Supplier<PlayerContext> contexts, Supplier<BlockBreaking> diggings, Interactions interactions,
            MovesToMainhand toMainhand, WalkTo walks, Supplier<Protection> protection) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.diggings = Objects.requireNonNull(diggings, "diggings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public boolean stillNight(TickContext context) {
        var level = context.player() == null ? null : context.player().level();
        // 熬到能睡的时段过去（天亮）；坑里看不到外面的怪，不拿威胁评估提前出坑。
        return level != null && WorldTime.canAttemptSleep(level);
    }

    @Override
    public BlockPos feet(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null ? null : self.blockPosition();
    }

    @Override
    public boolean onGround(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self != null && self.onGround();
    }

    @Override
    public BurrowPlan.Site site(TickContext context, BlockPos feet) {
        ClientLevel level = context.player().level();
        String dimension = level.dimension().location().toString();
        List<BurrowPlan.Ground> below = new ArrayList<>();
        List<BurrowPlan.Ground> walls = new ArrayList<>();
        for (int depth = 1; depth <= 4; depth++) {
            BlockPos cell = feet.below(depth);
            // 要挖的三格看保护；第四格只要踩得住，是谁的都不碰它。
            below.add(ground(level, cell, depth <= BurrowPlan.DEPTH ? dimension : null));
            if (depth <= BurrowPlan.DEPTH) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    walls.add(ground(level, cell.relative(side), null));
                }
            }
        }
        return new BurrowPlan.Site(below, walls);
    }

    // 一格是什么样；给了维度就再看它受不受保护（要挖的格子才看）。
    private BurrowPlan.Ground ground(ClientLevel level, BlockPos cell, String dimension) {
        BlockState state = level.getBlockState(cell);
        if (!state.getFluidState().isEmpty()) return BurrowPlan.Ground.FLUID;
        if (state.getCollisionShape(level, cell).isEmpty()) return BurrowPlan.Ground.OPEN;
        if (state.getDestroySpeed(level, cell) < 0) return BurrowPlan.Ground.UNBREAKABLE;
        if (dimension != null && !LiveCeilingDigs.mayDig(level, cell, protection.get())) {
            return BurrowPlan.Ground.PROTECTED;
        }
        return BurrowPlan.Ground.DIGGABLE;
    }

    @Override
    public BlockBreaking digging() {
        return diggings.get();
    }

    @Override
    public Optional<Action> sealing(BlockPos cell) {
        PlayerContext current = contexts.get();
        if (current == null || current.level() == null || current.backpack() == null) return Optional.empty();
        Optional<String> block = sealBlock(current.backpack());
        Optional<BlockPos> wall = wallBeside(current.level(), cell);
        if (block.isEmpty() || wall.isEmpty()) return Optional.empty();
        // 先把这块方块换到主手（已在主手就不用换），再对着坑壁朝坑口那一面点一下，方块落进坑口那格。
        List<Action> steps = new ArrayList<>();
        toMainhand.moveToMainhand(block.get()).ifPresent(steps::add);
        steps.add(interactions.useBlock(wall.get(), filled(cell)));
        return Optional.of(new StepwiseActions("封住坑口", steps.toArray(Action[]::new)));
    }

    // 封口用的方块：身上数量最多的普通建材；会掉落的、带方块实体的、贵重的不用。
    private static Optional<String> sealBlock(BackpackView backpack) {
        return backpack.stacks().stream()
                .filter(stack -> stack.buildingMaterial() && !stack.precious() && plainBlock(stack.itemId()))
                .max(Comparator.comparingInt(BackpackStack::count))
                .map(BackpackStack::itemId);
    }

    private static boolean plainBlock(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !(BuiltInRegistries.ITEM.get(id) instanceof BlockItem item)) return false;
        var block = item.getBlock();
        return !(block instanceof FallingBlock) && !(block instanceof EntityBlock);
    }

    // 坑口那格旁边一面实心的坑壁：点它朝坑口的那一面。从坑底看上去只露出这一面，瞄准落不到别处。
    private static Optional<BlockPos> wallBeside(ClientLevel level, BlockPos cell) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = cell.relative(side);
            if (!level.getBlockState(wall).getCollisionShape(level, wall).isEmpty()) return Optional.of(wall);
        }
        return Optional.empty();
    }

    // 封上了：坑口那格有了碰撞箱才算。
    private static InteractionConfirmation filled(BlockPos cell) {
        BlockPos frozen = cell.immutable();
        return context -> {
            if (!context.level().isLoaded(frozen)) return InteractionConfirmation.Verdict.PENDING;
            return context.level().getBlockState(frozen).getCollisionShape(context.level(), frozen).isEmpty()
                    ? InteractionConfirmation.Verdict.PENDING : InteractionConfirmation.Verdict.APPLIED;
        };
    }

    @Override
    public Action climbingTo(BlockPos surface) {
        // 只垫不挖：坑壁不动，脚下垫挖坑时捡到的方块一路升上去，站回挖坑前那一格。
        return walks.start(GoalCompiler.standOn(surface), TerrainPermit.TEMPORARY);
    }
}
