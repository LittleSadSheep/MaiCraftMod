// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 瞄准 → 交互 → 确认的完整动作，像真人一样"看准、点下去、看有没有反应"：
 * 先从现在的位置找出目标看得见的部位（哪一面看得见是游戏接口层的同一条事实），转过去并等真实镜头
 * 转到位（发出转头不算转到位），再用准星射线核对命中的确实是目标（命中了别的就重新瞄一次，
 * 还不行就是站位问题）；提交前冻结现场（目标格状态、射线命中者），经角色的交互提交入口交出本刻的
 * 一次交互，然后逐刻确认：连续几刻稳定才算数，客户端预测的回滚排除在外。
 * 结论分生效、没生效、出乎预料、没能确认四种；任务结束收尾时，没等到确认的提交按不确定交还，
 * 任何退出路径都不会漏。站位与靠近不归这里管：需要换位时用"到不了"的问题表达，
 * 由调用方换站位后重新做这个动作。
 */
public final class AimAndInteract implements Action {

    /** 这一下怎么点：右键目标本身（方块、实体），或者朝目标格使用手里的物品（水桶倒水这类沿物品自己射线作用的）。 */
    public enum Gesture {
        /** 右键方块或实体：开门、放方块、剪毛、骑乘。 */
        USE,
        /** 朝一格使用手里的物品：物品按自己的射线规则作用到那一格，例如把水倒进脚下那格。 */
        USE_HELD_ITEM
    }

    /** 等游戏确认的期限；超过就按没能确认收尾，不无限等。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 20;
    /** 镜头转向目标的耐心上限；原地转不过去说明视角被什么占住，按到不了收尾。 */
    private static final int TURN_PATIENCE_TICKS = 40;
    /** 转到位后准星点到了别的东西时，最多重新瞄几次；真人换个角度再试一次就够了。 */
    private static final int MAX_AIM_ATTEMPTS = 2;

    /** 动作内部的进度：转头与核对命中 → 等游戏确认。 */
    private enum Stage { AIMING, CONFIRMING }

    private final InteractionTarget target;
    private final Gesture gesture;
    private final InteractionHand hand;
    private final InteractionConfirmation confirmation;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    /** 这一次瞄准的点；重新瞄准时清空，按当时的位置重新找。 */
    private Vec3 aimPoint;
    private int aimAttempts;
    private int aimTicks;
    private Stage stage = Stage.AIMING;
    private PendingInteraction pending;
    /** 提交时冻结的现场：目标格当时的状态与提交的射线命中。 */
    private BlockState targetStateAtSubmission;
    private String submittedHitDescription = "";
    private InteractionResult result;
    private PlayerContext lastContext;

    public AimAndInteract(InteractionTarget target, InteractionConfirmation confirmation,
                          Function<PlayerContext, FirstPersonScene> scenes) {
        this(target, Gesture.USE, InteractionHand.MAIN_HAND, confirmation, scenes);
    }

    /**
     * @param target       要处理的目标；USE_HELD_ITEM 时是物品要作用到的那一格（倒水时是水该落进的那一格）
     * @param gesture      怎么点
     * @param hand         用哪只手
     * @param confirmation 什么时候算生效，由调用方按手势给
     */
    public AimAndInteract(InteractionTarget target, Gesture gesture, InteractionHand hand,
                          InteractionConfirmation confirmation, Function<PlayerContext, FirstPersonScene> scenes) {
        if (gesture == Gesture.USE_HELD_ITEM && !(target instanceof InteractionTarget.BlockTarget)) {
            throw new IllegalArgumentException("朝目标使用手里的物品只能对着一格方块");
        }
        this.gesture = Objects.requireNonNull(gesture, "gesture");
        this.hand = Objects.requireNonNull(hand, "hand");
        this.target = Objects.requireNonNull(target, "target");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    /** 做完之后的结论与现场；动作还没结束时没有结论。 */
    public InteractionResult result() {
        return result;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        lastContext = player;
        // 提交入口还没接上（或上下文已过期、本刻机会已被占用）时等下一刻，不冒进。
        if (!player.isCurrent() || player.interactionSender() == null || !player.canInteractThisTick()) {
            return ActionStatus.running();
        }
        FirstPersonScene scene = scenes.apply(player);
        return switch (stage) {
            case AIMING -> aimAndSubmit(player, scene);
            case CONFIRMING -> awaitConfirmation(player, scene);
        };
    }

    // 转头、核对命中、通过后当场冻结现场并提交；每刻只走一步。
    private ActionStatus aimAndSubmit(PlayerContext player, FirstPersonScene scene) {
        if (targetVanished(scene)) {
            return ActionStatus.failed(new Problem(Problem.Kind.TARGET_GONE,
                    target.describe() + "已经不在了，还没出手", null));
        }
        if (aimPoint == null) {
            aimPoint = planAim(scene);
            if (aimPoint == null) {
                return ActionStatus.failed(new Problem(Problem.Kind.UNREACHABLE,
                        "从当前位置看不到" + target.describe() + "的任何一面，或者够不着，需要换站位", null));
            }
        }
        player.input().halt(player.localPlayer());
        player.input().lookAt(player.localPlayer(), aimPoint);
        // 发出转头不算转到位：实际视线没对准前不发射线核对，避免点到目标旁边。
        if (!AimCheck.settled(scene.viewVector(), scene.eyePosition(), aimPoint)) {
            if (++aimTicks > TURN_PATIENCE_TICKS) {
                return ActionStatus.failed(new Problem(Problem.Kind.UNREACHABLE,
                        "镜头一直转不到" + target.describe() + "的方向", null));
            }
            return ActionStatus.running();
        }
        HitResult hit = scene.sightRay();
        boolean onTarget = gesture == Gesture.USE_HELD_ITEM
                ? scene.heldItemPointsAt(((InteractionTarget.BlockTarget) target).pos(), hand)
                : hitsTarget(hit);
        if (!onTarget) {
            return reaimOrGiveUp(hit);
        }
        return submit(player, scene, hit);
    }

    // 瞄准点：方块取现在看得见的那个部位，实体取碰撞箱中心；方块一面都看不到时为 null。
    private Vec3 planAim(FirstPersonScene scene) {
        if (target instanceof InteractionTarget.BlockTarget block) {
            BlockHitResult visible = gesture == Gesture.USE_HELD_ITEM
                    ? scene.visibleItemHit(block.pos(), hand)
                    : block.part() != null ? scene.visiblePartHit(block.pos(), block.part())
                    : scene.visibleHit(block.pos());
            return visible == null ? null : visible.getLocation();
        }
        return ((InteractionTarget.EntityTarget) target).entity().getBoundingBox().getCenter();
    }

    // 转到位后准星点到了别的：按现在的位置重新找瞄准点再试；次数用完就是站位问题。
    private ActionStatus reaimOrGiveUp(HitResult hit) {
        if (++aimAttempts < MAX_AIM_ATTEMPTS) {
            aimPoint = null;
            aimTicks = 0;
            return ActionStatus.running();
        }
        return ActionStatus.failed(new Problem(Problem.Kind.UNREACHABLE,
                "从当前位置看" + target.describe() + "被挡住：射线命中了" + AimCheck.describeHit(hit)
                        + "，需要换站位", null));
    }

    // 冻结提交现场（目标格状态、射线命中者）再交出本刻的交互；之后进入逐刻确认。
    private ActionStatus submit(PlayerContext player, FirstPersonScene scene, HitResult hit) {
        submittedHitDescription = AimCheck.describeHit(hit);
        if (gesture == Gesture.USE_HELD_ITEM) {
            // 物品按自己的射线作用：发的是"使用手里的物品"，不是右键准星指着的方块。
            targetStateAtSubmission = scene.blockAt(((InteractionTarget.BlockTarget) target).pos());
            pending = player.interactionSender().useItem(player, hand, confirmation, CONFIRM_TIMEOUT_TICKS);
        } else if (target instanceof InteractionTarget.BlockTarget block) {
            targetStateAtSubmission = scene.blockAt(block.pos());
            pending = player.interactionSender().useBlock(
                    player, hand, (BlockHitResult) hit, confirmation, CONFIRM_TIMEOUT_TICKS);
        } else {
            // 实体目标走右键实体的原生入口：剪毛、挤奶、骑乘、交易都是这一下。
            pending = player.interactionSender().interact(
                    player, ((InteractionTarget.EntityTarget) target).entity(), hand,
                    confirmation, CONFIRM_TIMEOUT_TICKS);
        }
        stage = Stage.CONFIRMING;
        return ActionStatus.progressed();
    }

    // 每刻核对确认记录；客户端会预测，稳定与否由游戏确认记录统一数，这里只看终态。
    private ActionStatus awaitConfirmation(PlayerContext player, FirstPersonScene scene) {
        if (pending.terminal()) {
            return settle(scene);
        }
        pending = player.interactionSender().poll(player, pending);
        return pending.terminal() ? settle(scene) : ActionStatus.running();
    }

    // 确认有了结论：如实附上现场（目标格提交前后的状态、提交时射线命中了谁）。
    private ActionStatus settle(FirstPersonScene scene) {
        result = InteractionResult.of(pending, sceneFacts(scene));
        return switch (result.verdict()) {
            case APPLIED -> ActionStatus.done();
            // 游戏明确没让它生效：换站位也救不了被拒绝的点击，如实带原因上报。
            case NOT_APPLIED -> ActionStatus.failed(new Problem(Problem.Kind.REFUSED_BY_GAME,
                    "对" + target.describe() + "的交互没有生效：" + result.scene(), null));
            // 世界变成了预料之外的样子，或结果说不清：交上层看现场，动作本身不再动第二次。
            case UNEXPECTED -> ActionStatus.failed(new Problem(Problem.Kind.STUCK,
                    "对" + target.describe() + "的交互结果出乎预料：" + result.scene(), null));
            case UNCONFIRMED -> ActionStatus.failed(new Problem(Problem.Kind.STUCK,
                    "提交了对" + target.describe() + "的交互但没能确认结果，不能盲目重做："
                            + result.scene(), null));
        };
    }

    // 现场说明：目标格提交前后的状态对比只在方块目标时有。
    private String sceneFacts(FirstPersonScene scene) {
        StringBuilder facts = new StringBuilder("提交时射线命中" + submittedHitDescription);
        if (target instanceof InteractionTarget.BlockTarget block && targetStateAtSubmission != null) {
            BlockState now = scene.blockAt(block.pos());
            facts.append("；目标格提交前是").append(targetStateAtSubmission.getBlock())
                    .append("，现在是").append(now == null ? "未加载" : now.getBlock().getName().getString());
        }
        return facts.toString();
    }

    // 目标还没加载不能当成没有；方块目标已经变成空气就是没了，实体目标已经死亡或离开世界也是没了。
    private boolean targetVanished(FirstPersonScene scene) {
        if (gesture == Gesture.USE_HELD_ITEM) {
            // 物品要作用的那一格本来就常是空气（水要倒进去），空不代表目标没了。
            return false;
        }
        if (target instanceof InteractionTarget.BlockTarget block) {
            BlockState state = scene.blockAt(block.pos());
            return state != null && state.isAir();
        }
        return target instanceof InteractionTarget.EntityTarget entity && entity.entity().isRemoved();
    }

    private boolean hitsTarget(HitResult hit) {
        if (target instanceof InteractionTarget.BlockTarget block) {
            return block.part() == null ? AimCheck.hitsBlock(hit, block.pos())
                    : AimCheck.hitsPart(hit, block.pos(), block.part());
        }
        return target instanceof InteractionTarget.EntityTarget entity
                && AimCheck.hitsEntity(hit, entity.entity());
    }

    @Override public void pause() {
        // 被生存需求打断：停步即可。已提交的确认继续等，挖到一半的交互不因暂停而丢。
        halt();
    }

    @Override public void close() {
        // 任务收尾：没等到确认的一次性提交按不确定交还，已提交的效果不假装撤销。
        if (pending != null && !pending.terminal() && lastContext != null
                && lastContext.interactionSender() != null) {
            lastContext.interactionSender().retireOneShotForTaskBoundary(
                    lastContext, pending, "交互动作结束前没能等到游戏确认");
            result = InteractionResult.of(pending, "动作收尾时仍未确认；提交时射线命中"
                    + submittedHitDescription);
        }
        halt();
    }

    private void halt() {
        if (lastContext != null && lastContext.isCurrent()) {
            lastContext.input().halt(lastContext.localPlayer());
        }
    }

    @Override public Interruptibility interruptibility() {
        // 正在等游戏确认时停下可能更危险（比如箱子刚点开、门刚点上），不在这个空当被打断。
        return pending != null && !pending.terminal()
                ? Interruptibility.UNSAFE_TO_STOP
                : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case AIMING -> "瞄准" + target.describe();
            case CONFIRMING -> "等待" + target.describe() + "的交互确认";
        };
    }
}
