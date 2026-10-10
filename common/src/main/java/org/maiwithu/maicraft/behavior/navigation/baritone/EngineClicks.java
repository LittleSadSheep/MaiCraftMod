// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.maiwithu.maicraft.game.interaction.BlockDigger;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 引擎在路上按下的左右键：左键挖开挡路的那一格，右键垫方块或开门。
 *
 * <p>引擎按下键之前已经自己转头对准、换好工具或垫块，准星此刻落在哪一格就是它要动的那一格；
 * 这里不另找目标，只把准星命中的方块交给游戏接口层去挖、去点，再等原生确认。
 * 能不能挖、能不能垫由引擎按任务许可和保护格先把关，到这里的都是获准动的。
 *
 * <p>一刻只做一件事：右键还在等确认时不挖也不再点；引擎松开左键就停挖，挖到一半的不留着。
 */
final class EngineClicks {

    /** 两次右键之间至少隔这么多刻：和原版按住右键的重复间隔一致，免得一块还没落稳又点下一块。 */
    private static final int USE_INTERVAL_TICKS = 4;
    /** 一次右键等游戏确认的上限。 */
    private static final int USE_CONFIRM_TICKS = 20;

    /** 这一刻动手的结果：没动手、正在动（挖掘推进或等确认）、真改了一格。 */
    enum Doing { NOTHING, WORKING, CHANGED_A_BLOCK }

    private BlockDigger digger;
    /** 挖掘器认的那个角色对象；重生或换世界后角色换了，挖掘器跟着换。 */
    private LocalPlayer diggerPlayer;
    /** 在等确认的那一次右键，以及点之前两格的样子：确认后据此认出垫上的方块。 */
    private PendingInteraction use;
    private BlockPos clicked;
    private BlockState clickedBefore;
    private BlockPos adjacent;
    private BlockState adjacentBefore;
    private int useCooldown;
    /** 最近一次右键确认后垫上的那一格；取走后清空。 */
    private BlockPos placed;

    /**
     * 推进一刻：先结清在途的右键，再按引擎此刻按着的键挖或点。
     *
     * @param left  引擎按着左键（挖）
     * @param right 引擎按着右键（垫块、开门）；和左键同时按时只挖
     * @param aimed 镜头已经转到引擎要的瞄点：没到位时不开新的挖、不点右键，已经在挖的接着挖
     */
    Doing tick(PlayerContext context, boolean left, boolean right, boolean aimed) {
        LocalPlayer player = context.localPlayer();
        if (digger == null || diggerPlayer != player) {
            digger = new BlockDigger(player, context.interactionSender(), context.menuActions(), context.input());
            diggerPlayer = player;
        }
        if (use != null) {
            return settleUse(context);
        }
        if (useCooldown > 0) useCooldown--;
        // 挖的那一格已经没了：准星打不到它了，只结清这次挖掘的确认；确认是自己挖开的才算动了一格。
        BlockPos digging = digger.current();
        if (digging != null && context.level().getBlockState(digging).isAir()) {
            return switch (digger.settleGone(context, true)) {
                case BROKE_TARGET, BROKE_OCCLUDER -> Doing.CHANGED_A_BLOCK;
                case PROGRESSING -> Doing.WORKING;
                case NO_SHOT -> Doing.NOTHING;
            };
        }
        if (left) {
            // 镜头还在转：准星此刻路过的格不是引擎要挖的那一格，等转到位再下手；已经开挖的接着挖。
            if (!aimed && digging == null) {
                digger.tickCooldown();
                return Doing.NOTHING;
            }
            return dig(context, player);
        }
        // 引擎松开了左键（换段、撤路线或改成别的动作）：挖到一半的停手，不让它一直按着。
        if (digging != null) {
            digger.cancel(context);
        } else {
            digger.tickCooldown();
        }
        if (right && useCooldown == 0 && aimed) {
            return startUse(context, player);
        }
        return Doing.NOTHING;
    }

    /** 取走最近一次右键垫上的那一格；没垫过给空。 */
    Optional<BlockPos> takePlaced() {
        Optional<BlockPos> result = Optional.ofNullable(placed);
        placed = null;
        return result;
    }

    /**
     * 走到停下、暂停或被放弃：停挖，没等到确认的右键按不确定交还，已发出的效果不假装撤销。
     * 拿不到当刻的角色上下文时，停挖留到下一刻在别的动作之前做。
     */
    void stop(PlayerContext context) {
        boolean current = context != null && context.isCurrent();
        if (digger != null) {
            digger.cancel(current ? context : null);
        }
        if (use != null && !use.terminal() && context != null) {
            if (current) {
                context.interactionSender().retireOneShotForTaskBoundary(context, use, "走到停下时右键还没等到游戏确认");
            } else {
                context.interactionSender().abandonOneShotForTaskBoundary(use, "走到停下时右键还没等到游戏确认");
            }
        }
        use = null;
    }

    // 左键：准星打在方块上才挖，挖的就是准星命中的那一格那一面；工具由引擎自己换好。
    private Doing dig(PlayerContext context, LocalPlayer player) {
        HitResult aimed = Interaction.nativeRaytrace(player, InteractionRange.blockReach(player));
        if (!(aimed instanceof BlockHitResult hit) || aimed.getType() != HitResult.Type.BLOCK) {
            digger.tickCooldown();
            return digger.hasPendingBreak() ? Doing.WORKING : Doing.NOTHING;
        }
        return switch (digger.digStep(context, hit)) {
            case BROKE_TARGET, BROKE_OCCLUDER -> Doing.CHANGED_A_BLOCK;
            case PROGRESSING -> Doing.WORKING;
            case NO_SHOT -> Doing.NOTHING;
        };
    }

    // 右键：准星打在方块上、这一刻还有交互机会才点；点之前记下被点的那格和贴着那一面的那格，
    // 哪一格变了、拿在手上的东西变了，都算这一下生效（垫块落下、门开关、手上的方块少了一个）。
    private Doing startUse(PlayerContext context, LocalPlayer player) {
        HitResult aimed = Interaction.nativeRaytrace(player, InteractionRange.blockReach(player));
        if (!(aimed instanceof BlockHitResult hit) || aimed.getType() != HitResult.Type.BLOCK
                || player.isHandsBusy() || !context.canInteractThisTick()) {
            return Doing.NOTHING;
        }
        clicked = hit.getBlockPos().immutable();
        adjacent = clicked.relative(hit.getDirection()).immutable();
        clickedBefore = context.level().getBlockState(clicked);
        adjacentBefore = context.level().getBlockState(adjacent);
        ItemStack heldBefore = player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        InteractionConfirmation changed = InteractionConfirmation.anyOf(
                InteractionConfirmation.blockChanged(clicked, clickedBefore),
                InteractionConfirmation.blockChanged(adjacent, adjacentBefore),
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, heldBefore));
        try {
            use = context.interactionSender().useBlock(context, InteractionHand.MAIN_HAND, hit, changed,
                    USE_CONFIRM_TICKS);
        } catch (IllegalStateException busy) {
            // 另一个原生动作还在等确认，这一刻点不了；引擎下一刻还按着就再试。
            return Doing.NOTHING;
        }
        return settleUse(context);
    }

    // 等右键的确认：生效了就看是哪一格长出了方块，记成垫上的；没生效或拿不准也放下，过一会儿引擎再按就再点。
    private Doing settleUse(PlayerContext context) {
        if (!use.terminal()) {
            use = context.interactionSender().poll(context, use);
        }
        if (!use.terminal()) {
            return Doing.WORKING;
        }
        boolean applied = use.status() == PendingInteraction.Status.CONFIRMED_APPLIED;
        use = null;
        useCooldown = USE_INTERVAL_TICKS;
        if (!applied) {
            return Doing.NOTHING;
        }
        if (placedInto(adjacentBefore, context.level().getBlockState(adjacent))) {
            placed = adjacent;
        } else if (placedInto(clickedBefore, context.level().getBlockState(clicked))) {
            // 点在草丛、雪层这类能被顶替的格子上时，方块直接落进被点的那一格。
            placed = clicked;
        }
        return Doing.CHANGED_A_BLOCK;
    }

    /** 这一格是不是被垫上了方块：原来能被顶替（空气、水、草丛），现在是一块不同的实心方块。 */
    static boolean placedInto(BlockState before, BlockState after) {
        return before.canBeReplaced() && !after.isAir() && !after.liquid()
                && !after.canBeReplaced() && after.getBlock() != before.getBlock();
    }
}
