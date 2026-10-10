// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
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
 * 按住不放的持续使用，两种起手：进食、拉弓这类只按住手中物品的；刷子刷可疑的沙子、沙砾
 * 这类对着方块一直按住的。先确认这次使用真的开始了，之后每刻核对"做成没有"的确认条件，
 * 同时逐刻续期按住使用键的投影——原版每个客户端刻读一次使用键，看到松键就把使用松开，
 * 投影一断进食就停、刷子就停。对格按住时先把准星转到目标看得见的部位再右键；按住期间
 * 视线被带偏就悄悄转回来，被原版松开（视线移开、使用到期、被打断）而还没做成，就重新瞄准
 * 再点，像真人接着刷一样；目标格中途空了（被挖掉、塌走）按目标没了收场。确认条件达成
 * （吃完了、方块刷成普通沙子）就原生松手并等松开确认；按住到期、归属不再成立（游戏确认
 * 记录换了主人）时同样松手，不把一次没做成的持续使用硬算成功。任务收尾时投影一并交还，
 * 任何退出路径都不会让角色一直按着使用键。
 */
public final class SustainedUse implements Action {

    /** 等这次使用开始的期限；点下去没有任何反应就按没生效收尾。 */
    private static final int START_TIMEOUT_TICKS = 20;

    /** 镜头转向目标的耐心上限；原地转不过去说明视角被什么占住，按到不了收尾。 */
    private static final int TURN_PATIENCE_TICKS = 40;
    /** 转到位后准星点到了别的东西时，最多重新瞄几次；真人换个角度再试一次就够了。 */
    private static final int MAX_AIM_ATTEMPTS = 2;
    /** 按住被原版松开后重新瞄准再点的次数上限；反复接不上说明现场留不住按住，如实收场。 */
    private static final int MAX_RESTARTS = 4;

    /** 动作内部的进度：对格先瞄准 → 点下去等开始 → 按住并逐刻核对 → 松手等收尾。 */
    private enum Stage { AIMING, STARTING, HOLDING, RELEASING }

    private final InteractionHand hand;
    /** 什么时候算"做成了"：由调用方给（例如手里食物变少、目标格刷成普通方块），本动作不认识具体物品。 */
    private final InteractionConfirmation done;
    private final UseKeyProjection projection;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    /** 对着这一格方块按住（刷子刷可疑方块）；为 null 表示只按住手中的物品，不用对准。 */
    private final BlockPos targetCell;
    /** 最多按住多少刻；零或负数表示等物品自行结束（例如吃完一块面包）。 */
    private final int maxHoldTicks;

    private Stage stage;
    private PendingInteraction pending;
    private ItemStack heldAtSubmission;
    private Vec3 aimPoint;
    private int aimAttempts;
    private int aimTicks;
    private int restarts;
    private int heldTicks;
    private String releaseReason = "";
    private InteractionResult result;
    private PlayerContext lastContext;

    public SustainedUse(InteractionHand hand, InteractionConfirmation done,
                        UseKeyProjection projection, int maxHoldTicks,
                        Function<PlayerContext, FirstPersonScene> scenes) {
        this(hand, done, projection, maxHoldTicks, scenes, null);
    }

    /**
     * @param targetCell 对着这一格方块按住；先瞄准、右键开始，按住期间保持对准，
     *                   被原版松开就重新瞄准再点，直到做成或预算用完
     */
    public SustainedUse(InteractionHand hand, InteractionConfirmation done,
                        UseKeyProjection projection, int maxHoldTicks,
                        Function<PlayerContext, FirstPersonScene> scenes, BlockPos targetCell) {
        this.hand = Objects.requireNonNull(hand, "hand");
        this.done = Objects.requireNonNull(done, "done");
        this.projection = projection;
        this.maxHoldTicks = maxHoldTicks;
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.targetCell = targetCell == null ? null : targetCell.immutable();
        this.stage = targetCell == null ? Stage.STARTING : Stage.AIMING;
    }

    /** 做完之后的结论与现场；动作还没结束时没有结论。瞄准与目标没了这类没做成的收场留给调用方处理。 */
    public InteractionResult result() {
        return result;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        lastContext = player;
        if (!player.isCurrent() || player.interactionSender() == null || !player.canInteractThisTick()) {
            return ActionStatus.running();
        }
        return switch (stage) {
            case AIMING -> aimAndClick(player);
            case STARTING -> startUse(player);
            case HOLDING -> holdAndWatch(player);
            case RELEASING -> awaitRelease(player);
        };
    }

    // 对格按住的第一步：转向目标看得见的部位，等镜头转到位、核对准星真的点在这格上，再右键开始按住。
    private ActionStatus aimAndClick(PlayerContext player) {
        FirstPersonScene scene = scenes.apply(player);
        // 目标格变成空气（被挖掉、沙子塌走）就是目标没了，不把一次坏目标当成刷不过。
        BlockState live = scene.blockAt(targetCell);
        if (live != null && live.isAir()) {
            return ActionStatus.failed(new Problem(Problem.Kind.TARGET_GONE,
                    "目标格 " + targetCell.toShortString() + " 已经空了，还没开始按住", null));
        }
        if (aimPoint == null) {
            BlockHitResult visible = scene.visibleHit(targetCell);
            if (visible == null) {
                return ActionStatus.failed(new Problem(Problem.Kind.UNREACHABLE,
                        "从当前位置看不到 " + targetCell.toShortString() + " 的任何一面，需要换站位", null));
            }
            aimPoint = visible.getLocation();
        }
        player.input().halt(player.localPlayer());
        player.input().lookAt(player.localPlayer(), aimPoint);
        // 发出转头不算转到位：实际视线没对准前不发射线核对，避免点到目标旁边。
        if (!AimCheck.settled(scene.viewVector(), scene.eyePosition(), aimPoint)) {
            if (++aimTicks > TURN_PATIENCE_TICKS) {
                return ActionStatus.failed(new Problem(Problem.Kind.UNREACHABLE,
                        "镜头一直转不到 " + targetCell.toShortString() + " 的方向", null));
            }
            return ActionStatus.running();
        }
        HitResult hit = scene.sightRay();
        if (!AimCheck.hitsBlock(hit, targetCell)) {
            if (++aimAttempts < MAX_AIM_ATTEMPTS) {
                aimPoint = null;
                aimTicks = 0;
                return ActionStatus.running();
            }
            return ActionStatus.failed(new Problem(Problem.Kind.UNREACHABLE,
                    "从当前位置对 " + targetCell.toShortString() + " 按住被挡住：射线命中了"
                            + AimCheck.describeHit(hit) + "，需要换站位", null));
        }
        // 转到位且准星点在目标格上：冻结手上的东西，右键这一面开始按住；原版随即进入使用状态。
        heldAtSubmission = scene.heldItem(hand);
        pending = player.interactionSender().useBlock(player, hand, (BlockHitResult) hit,
                startConfirmation(), START_TIMEOUT_TICKS);
        stage = Stage.STARTING;
        return ActionStatus.progressed();
    }

    // 右键一次手中物品（只按住物品时），开始确认是"手里东西变了、或已经在使用中"这样的客户端事实。
    private ActionStatus startUse(PlayerContext player) {
        if (pending == null) {
            FirstPersonScene scene = scenes.apply(player);
            heldAtSubmission = scene.heldItem(hand);
            pending = player.interactionSender().useItem(player, hand, startConfirmation(), START_TIMEOUT_TICKS);
            return ActionStatus.running();
        }
        if (!pending.terminal()) {
            pending = player.interactionSender().poll(player, pending);
        }
        if (!pending.terminal()) {
            return ActionStatus.running();
        }
        if (pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
            result = InteractionResult.of(pending, "手持" + describeHeld() + "的持续使用没有开始");
            return failureFrom(result);
        }
        stage = Stage.HOLDING;
        return ActionStatus.progressed();
    }

    // 开始确认：手上东西变了（吃掉一口、弓开始蓄力会换状态）或正在使用中，都算这次使用已生效。
    // 现场快照缺失（测试替身读不到手持）时只按"正在使用中"核对，不冒充看到了物品变化。
    private InteractionConfirmation startConfirmation() {
        ItemStack before = heldAtSubmission;
        return context -> {
            if (before != null && InteractionConfirmation.heldItemChanged(hand, before).observe(context)
                    == InteractionConfirmation.Verdict.APPLIED) {
                return InteractionConfirmation.Verdict.APPLIED;
            }
            return context.usingItem()
                    ? InteractionConfirmation.Verdict.APPLIED
                    : InteractionConfirmation.Verdict.PENDING;
        };
    }

    // 按住期间每刻做几件事：续投影让原版继续看到"按住"，镜头被带偏就转回来，核对做成没有，
    // 对格按住再看目标格还在不在、这次使用还归不归本动作管、按住有没有到期。
    private ActionStatus holdAndWatch(PlayerContext player) {
        if (projection != null) {
            projection.renew(this, player, pending, hand, heldAtSubmission);
        }
        FirstPersonScene scene = targetCell == null ? null : scenes.apply(player);
        if (scene != null && keepAim(player, scene)) {
            return ActionStatus.progressed();
        }
        if (done.observe(player) == InteractionConfirmation.Verdict.APPLIED) {
            return release(player, "确认条件已达成");
        }
        if (scene != null && targetVanished(scene)) {
            // 按住中途目标格空了（被人挖掉、塌走）：交还投影，原版两个刻内就看到松键自己松手；
            // 刷了一半不算刷完，按目标没了收场，由调用方决定接下来怎么办。
            if (projection != null) projection.release(this);
            return ActionStatus.failed(new Problem(Problem.Kind.TARGET_GONE,
                    "目标格 " + targetCell.toShortString() + " 在按住时空了，已松手", null));
        }
        // 原版把按住松开了（视线移开、使用到期、被打断）而还没做成：重新瞄准再点，真人就是这么续的。
        if (targetCell != null && !player.usingItem()) {
            if (++restarts > MAX_RESTARTS) {
                if (projection != null) projection.release(this);
                result = InteractionResult.unconfirmed("对 " + targetCell.toShortString()
                        + " 按住了 " + restarts + " 轮还没做成，不再接着按");
                return ActionStatus.failed(new Problem(Problem.Kind.STUCK, result.scene(), null));
            }
            stage = Stage.AIMING;
            aimPoint = null;
            aimAttempts = 0;
            aimTicks = 0;
            return ActionStatus.progressed();
        }
        // 归属不再成立（确认记录被游戏收回或换了主人）时按住已无意义，松手并如实报告。
        if (!player.interactionSender().ownsItemUse(pending)) {
            if (projection != null) projection.release(this);
            result = InteractionResult.unexpected(
                    "手持" + describeHeld() + "的持续使用不再归本动作管，已松手");
            return ActionStatus.failed(new Problem(Problem.Kind.STUCK, result.scene(), null));
        }
        if (maxHoldTicks > 0 && ++heldTicks >= maxHoldTicks) {
            return release(player, "按住已到 " + maxHoldTicks + " 刻的上限");
        }
        // 持续使用本身就是进展：吃东西的每一刻都在少一口，不算原地打转。
        return ActionStatus.progressed();
    }

    // 按住期间镜头被带偏：先悄悄转回原瞄准点，原版射线还留在方块上就不用松手重来。转回来了返回真。
    private boolean keepAim(PlayerContext player, FirstPersonScene scene) {
        if (AimCheck.settled(scene.viewVector(), scene.eyePosition(), aimPoint)) {
            return false;
        }
        player.input().halt(player.localPlayer());
        player.input().lookAt(player.localPlayer(), aimPoint);
        return true;
    }

    // 目标格还没加载不能当成没有；变成空气就是没了。
    private boolean targetVanished(FirstPersonScene scene) {
        BlockState state = scene.blockAt(targetCell);
        return state != null && state.isAir();
    }

    // 松开使用并等松开确认；松开之后再看一次做成没有，没等到就按没能确认收尾。
    private ActionStatus release(PlayerContext player, String why) {
        pending = player.interactionSender().releaseUsingItem(player, pending);
        stage = Stage.RELEASING;
        releaseReason = why;
        return ActionStatus.running();
    }

    private ActionStatus awaitRelease(PlayerContext player) {
        if (pending != null && !pending.terminal()) {
            pending = player.interactionSender().poll(player, pending);
            if (pending != null && !pending.terminal()) {
                return ActionStatus.running();
            }
        }
        if (done.observe(player) == InteractionConfirmation.Verdict.APPLIED) {
            result = InteractionResult.applied("手持" + describeHeld() + "的持续使用完成（" + releaseReason + "）");
            return ActionStatus.done();
        }
        result = InteractionResult.unconfirmed("松开了手持" + describeHeld() + "的持续使用（"
                + releaseReason + "），但没等到做成它的确认，不能盲目再试");
        return ActionStatus.failed(new Problem(Problem.Kind.STUCK, result.scene(), null));
    }

    private String describeHeld() {
        // 只说提交时的数量，不报物品注册名：注册名要过注册表，现场说明里不是必需的。
        return heldAtSubmission == null ? "手持物品" : "手持物品（提交时 " + heldAtSubmission.getCount() + " 件）";
    }

    private ActionStatus failureFrom(InteractionResult outcome) {
        Problem.Kind kind = outcome.verdict() == InteractionVerdict.NOT_APPLIED
                ? Problem.Kind.REFUSED_BY_GAME : Problem.Kind.STUCK;
        return ActionStatus.failed(new Problem(kind, outcome.scene(), null));
    }

    @Override public void pause() {
        // 被生存需求打断时不主动松手：投影在两个游戏刻内自然过期，原版看到松键，进食就此停住；
        // 已消耗的一口如实留在世界里，恢复与否由上层任务决定。对格按住恢复后由重新瞄准再点接上。
    }

    @Override public void close() {
        // 任务收尾：先交还投影，再尽量原生松手。按住阶段开始确认已通过，就算确认记录已经
        // 终结，角色仍可能在按着使用键——松手请求必须发出去，不能只等投影过期。
        if (projection != null) {
            projection.release(this);
        }
        // 本刻已经没有交互机会时发不了松开：投影已经交还，原版两刻内就看到松键，不硬发第二下。
        if (stage == Stage.HOLDING && lastContext != null && lastContext.interactionSender() != null
                && pending != null && lastContext.canInteractThisTick()) {
            pending = lastContext.interactionSender().releaseUsingItem(lastContext, pending);
            result = InteractionResult.unconfirmed(
                    "任务结束时松开了手持" + describeHeld() + "的持续使用，没能等到做成它的确认");
        }
    }

    @Override public Interruptibility interruptibility() {
        return pending != null && !pending.terminal()
                ? Interruptibility.UNSAFE_TO_STOP
                : Interruptibility.WORKING;
    }

    @Override public String describe() {
        String on = targetCell == null ? "" : " " + targetCell.toShortString();
        return switch (stage) {
            case AIMING -> "瞄准" + on + "准备按住";
            case STARTING -> "开始使用手持物品";
            case HOLDING -> "按住使用手持物品" + on + "（第 " + heldTicks + " 刻）";
            case RELEASING -> "松开手持物品的使用";
        };
    }
}
