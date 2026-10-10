// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.GestureConfirmations;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.interaction.SustainedUse;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 组装这一下交互的生产实现：出手前冻结现场，按手上实际拿着的东西归手势，按手势挑点哪一格、怎样算生效。
 *
 * <p>对实体用：手上变了、点开了界面、骑了上去，有一项成立就算生效。写告示牌：编辑界面打开才算点上了。
 * 桶按自己的射线作用（舀只认源格、倒在被点面外面），所以舀和倒是"朝那一格使用手里的物品"，
 * 不是右键准星下的方块；其余都是右键要点的那一格。方块有没有自己的右键行为、能不能直接点着，
 * 按方块的种类与它给不给界面判断，不按名字猜。
 */
final class LiveUseInteractions implements UseSeams.BuildsInteraction {

    /** 对格按住刷的按住上限。F 游戏事实：一次连续按住约 100 刻就能刷完（十下、每十刻一下），
     * 上限是"反复被打断还刷不完"的卡住兜底，不是计时猜刷没刷完——做成以方块状态变化为准。 */
    private static final int BRUSH_HOLD_LIMIT_TICKS = 600;

    private final Interactions interactions;
    private final Supplier<PlayerContext> context;

    LiveUseInteractions(Interactions interactions, Supplier<PlayerContext> context) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public UseSeams.Built build(ResolvedTarget target, boolean writesSign) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return new UseSeams.Built.CannotAim(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，出不了手", null));
        }
        LocalPlayer player = current.localPlayer();
        ItemStack handBefore = player.getMainHandItem().copy();
        UseSeams.ReadsWorld.Held held = handBefore.isEmpty() ? null : new UseSeams.ReadsWorld.Held(
                BuiltInRegistries.ITEM.getKey(handBefore.getItem()).toString(), handBefore.getCount());
        ItemUseAim.Gesture gesture = ItemUseAim.gestureOf(held == null ? null : held.itemId());
        int menuBefore = player.containerMenu.containerId;
        if (target == null) {
            // 只对手上的东西用（喝药水、吹山羊角）：按住到物品自己用完，手上的东西变了就算生效。
            // 刷子没有对着空气的使用方式，不给目标没法刷。
            if (gesture == ItemUseAim.Gesture.BRUSH) {
                return new UseSeams.Built.CannotAim(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "刷子要对着方块刷（可疑的沙子、沙砾），不给目标没法刷", null));
            }
            SustainedUse use = interactions.useHeldItem(InteractionHand.MAIN_HAND,
                    InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, handBefore), 0);
            return new UseSeams.Built.Ready(use, use::result, gesture, held, menuBefore, null);
        }
        if (target.isEntity()) {
            return onEntity(current.level(), target, handBefore, held, gesture, menuBefore);
        }
        return onBlock(current.level(), player, target, writesSign, handBefore, held, gesture, menuBefore);
    }

    // 对实体用：按编号找回当刻的它；手上变了（挤到奶）、点开了界面（村民）、骑上去了（马、船），都算生效。
    private UseSeams.Built onEntity(ClientLevel level, ResolvedTarget target, ItemStack handBefore,
            UseSeams.ReadsWorld.Held held, ItemUseAim.Gesture gesture, int menuBefore) {
        Entity entity = level.getEntity(target.entityId());
        if (entity == null || entity.isRemoved()) {
            return new UseSeams.Built.CannotAim(Problem.of(Problem.Kind.TARGET_GONE,
                    target.describe() + " 已经不在了，还没出手", "重新 observe"));
        }
        AimAndInteract use = interactions.useEntity(entity, GestureConfirmations.anyOf(
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, handBefore),
                InteractionConfirmation.menuChanged(menuBefore),
                InteractionConfirmation.ridingOn(entity)));
        return new UseSeams.Built.Ready(use, use::result, gesture, held, menuBefore, null);
    }

    // 对方块用：写字只认编辑界面打开；其余按手势定点哪一格、效果落在哪一格、怎样算生效。
    private UseSeams.Built onBlock(ClientLevel level, LocalPlayer player, ResolvedTarget target, boolean writesSign,
            ItemStack handBefore, UseSeams.ReadsWorld.Held held, ItemUseAim.Gesture gesture, int menuBefore) {
        BlockPos cell = target.cell();
        if (!level.hasChunkAt(cell)) {
            return new UseSeams.Built.CannotAim(Problem.of(Problem.Kind.UNREACHABLE, "目标那一格没加载", null));
        }
        BlockState state = level.getBlockState(cell);
        if (writesSign) {
            AimAndInteract use = interactions.useBlock(cell, InteractionConfirmation.signEditorScreenOpened());
            return new UseSeams.Built.Ready(use, use::result, gesture, held, menuBefore, null);
        }
        // 刷子：右键一下只是开始，之后一直按住直到那一格变回普通方块（原版把可疑方块整格换掉）。
        // 做成以方块状态变化为准，不靠计时猜；刷出的东西由任务的捡起阶段收回背包。
        if (gesture == ItemUseAim.Gesture.BRUSH) {
            SustainedUse brush = interactions.useBlockSustained(cell,
                    InteractionConfirmation.blockChangedNotAir(cell, state), BRUSH_HOLD_LIMIT_TICKS);
            return new UseSeams.Built.Ready(brush, brush::result, gesture, held, menuBefore, null);
        }
        Optional<ItemUseAim.Aim> planned = ItemUseAim.aim(gesture, cell, natureOf(state),
                aroundOf(level, player, cell, state));
        if (planned.isEmpty()) {
            return new UseSeams.Built.CannotAim(whyNoAim(gesture, target));
        }
        ItemUseAim.Aim aim = planned.get();
        var confirmation = GestureConfirmations.forGesture(aim.confirmation(), aim.effectCell(),
                InteractionHand.MAIN_HAND, handBefore, level.getBlockState(aim.clickCell()), menuBefore);
        // 桶按自己的射线作用：朝效果那一格使用手里的物品；别的东西右键要点的那一格。
        boolean bucket = gesture == ItemUseAim.Gesture.SCOOP || gesture == ItemUseAim.Gesture.POUR;
        AimAndInteract use = bucket
                ? interactions.useHeldItemToward(aim.effectCell(), InteractionHand.MAIN_HAND, confirmation)
                : interactions.useBlock(aim.clickCell(), confirmation);
        return new UseSeams.Built.Ready(use, use::result, gesture, held, menuBefore, aim.effectCell());
    }

    // 瞄不出来的原因按手势说：工具与空桶是这一格用不了；倒和点火是站位问题，换个站位还有机会。
    private static Problem whyNoAim(ItemUseAim.Gesture gesture, ResolvedTarget target) {
        return switch (gesture) {
            case SCOOP -> Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    target.describe() + " 不是流体源格，空桶舀不起来", null);
            case TRANSFORM_TOOL -> Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    target.describe() + " 不是实心方块或上面压着东西，锄、锹、斧用不上", null);
            case POUR, IGNITE -> Problem.of(Problem.Kind.UNREACHABLE,
                    "从这里对 " + target.describe() + " 下手，效果会落到自己身上或旁边没有能点的面", null);
            default -> Problem.of(Problem.Kind.UNSUPPORTED, "手上的东西 use 不接", null);
        };
    }

    // 目标格此刻的样子归成手势判定要问的四类。
    private static ItemUseAim.CellNature natureOf(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().isSource() ? ItemUseAim.CellNature.FLUID_SOURCE
                    : ItemUseAim.CellNature.FLUID_FLOWING;
        }
        return state.isAir() || state.canBeReplaced() ? ItemUseAim.CellNature.AIR_OR_REPLACEABLE
                : ItemUseAim.CellNature.SOLID;
    }

    // 出手前的目标周围事实：能当支撑面的邻居、自己有右键行为的邻居、上方是否敞开、能不能点着它本身。
    private static ItemUseAim.Around aroundOf(ClientLevel level, LocalPlayer player, BlockPos cell, BlockState state) {
        Set<Direction> solid = new HashSet<>();
        Set<Direction> clickable = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos at = cell.relative(direction);
            BlockState neighbor = level.getBlockState(at);
            if (!neighbor.isAir() && neighbor.getFluidState().isEmpty()) solid.add(direction);
            if (handlesRightClick(level, at, neighbor)) clickable.add(direction);
        }
        return new ItemUseAim.Around(player.blockPosition(), solid, clickable,
                level.getBlockState(cell.above()).isAir(), ignitesItself(state));
    }

    // 右键会先触发方块自己的行为：开界面的（箱子、熔炉、工作台、织布机）、带方块实体的、门与活板门、
    // 栅栏门、按钮、拉杆。手里的东西点上去会被它截走，倒流体、点火挑支撑面时避开。
    private static boolean handlesRightClick(ClientLevel level, BlockPos at, BlockState state) {
        if (state.hasBlockEntity() || state.getMenuProvider(level, at) != null) return true;
        var block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock;
    }

    // 点它本身就能点着：TNT，还没点着的营火、蜡烛、蜡烛蛋糕。
    private static boolean ignitesItself(BlockState state) {
        return state.getBlock() instanceof TntBlock || CampfireBlock.canLight(state)
                || CandleBlock.canLight(state) || CandleCakeBlock.canLight(state);
    }
}
