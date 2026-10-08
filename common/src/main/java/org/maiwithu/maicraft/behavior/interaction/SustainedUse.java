// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 进食、拉弓这类按住不放的持续使用：先右键一次并确认这次使用真的开始了，
 * 之后每刻核对"做成没有"的确认条件，同时逐刻续期按住使用键的投影——原版每个
 * 客户端刻读一次使用键，投影断一口进食就停。确认条件达成（吃完了、箭放出去了）
 * 就原生松手并等松开确认；按住到期、归属不再成立（游戏确认记录换了主人）时同样
 * 松手，不把一次没做成的持续使用硬算成功。任务收尾时投影一并交还，任何退出
 * 路径都不会让角色一直按着使用键。
 */
public final class SustainedUse implements Action {

    /** 等这次使用开始的期限；点下去没有任何反应就按没生效收尾。 */
    private static final int START_TIMEOUT_TICKS = 20;

    /** 动作内部的进度：点下去等开始 → 按住并逐刻核对 → 松手等收尾。 */
    private enum Stage { STARTING, HOLDING, RELEASING }

    private final InteractionHand hand;
    /** 什么时候算"做成了"：由调用方给（例如手里食物变少、箭已经离手），本动作不认识具体物品。 */
    private final InteractionConfirmation done;
    private final UseKeyProjection projection;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    /** 最多按住多少刻；零或负数表示等物品自行结束（例如吃完一块面包）。 */
    private final int maxHoldTicks;

    private Stage stage = Stage.STARTING;
    private PendingInteraction pending;
    private ItemStack heldAtSubmission;
    private int heldTicks;
    private String releaseReason = "";
    private InteractionResult result;
    private PlayerContext lastContext;

    public SustainedUse(InteractionHand hand, InteractionConfirmation done,
                        UseKeyProjection projection, int maxHoldTicks,
                        Function<PlayerContext, FirstPersonScene> scenes) {
        this.hand = Objects.requireNonNull(hand, "hand");
        this.done = Objects.requireNonNull(done, "done");
        this.projection = projection;
        this.maxHoldTicks = maxHoldTicks;
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    /** 做完之后的结论与现场；动作还没结束时没有结论。 */
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
            case STARTING -> startUse(player);
            case HOLDING -> holdAndWatch(player);
            case RELEASING -> awaitRelease(player);
        };
    }

    // 右键一次手中物品，开始确认是"手里东西变了、或已经在使用中"这样的客户端事实。
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
            return context.localPlayer() != null && context.localPlayer().isUsingItem()
                    ? InteractionConfirmation.Verdict.APPLIED
                    : InteractionConfirmation.Verdict.PENDING;
        };
    }

    // 按住期间每刻做三件事：续投影让原版继续看到"按住"，核对做成没有，检查这次使用还归不归本动作管。
    private ActionStatus holdAndWatch(PlayerContext player) {
        if (projection != null) {
            projection.renew(this, player, pending, hand, heldAtSubmission);
        }
        if (done.observe(player) == InteractionConfirmation.Verdict.APPLIED) {
            return release(player, "确认条件已达成");
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
        // 已消耗的一口如实留在世界里，恢复与否由上层任务决定。
    }

    @Override public void close() {
        // 任务收尾：先交还投影，再尽量原生松手。按住阶段开始确认已通过，就算确认记录已经
        // 终结，角色仍可能在按着使用键——松手请求必须发出去，不能只等投影过期。
        if (projection != null) {
            projection.release(this);
        }
        if (stage == Stage.HOLDING && lastContext != null && lastContext.interactionSender() != null
                && pending != null) {
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
        return switch (stage) {
            case STARTING -> "开始使用手持物品";
            case HOLDING -> "持续使用手持物品（第 " + heldTicks + " 刻）";
            case RELEASING -> "松开手持物品的使用";
        };
    }
}
