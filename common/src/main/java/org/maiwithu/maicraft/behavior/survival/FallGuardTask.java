// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.OptionalInt;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 落地防护临时任务（落地放水）：这一掉会摔死时，像真人一样换上快捷栏里的水桶、低头，
 * 等落点进入够得着的距离就把水倒在落点上，落进水里抵掉这次坠落。
 *
 * <p>顺序：换水桶 → 低头等落点够得着 → 倒水 → 等落地。落点本来就是水时不用做任何事；
 * 快捷栏里没有水桶就如实说做不了，落地伤害由游戏结算。放下的水留在落点，结果里写清楚，要不要收回由上层决定。
 */
public final class FallGuardTask extends PhasedTask<FallGuardTask.Phase> {

    /** 触发条件：预计落地伤害够得到当前生命，或正下方是虚空；多犹豫一刻高度就少一段。 */
    public enum Phase { TAKE_BUCKET, LOOK_DOWN, POUR, LAND }

    private static final String WATER_BUCKET = "minecraft:water_bucket";

    /** 换水桶这一下等游戏确认的期限。 */
    private static final int SELECT_TIMEOUT_TICKS = 5;

    private final SurvivalSituation.SituationReader reader;
    private final Interactions interactions;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    private final Function<PlayerContext, BackpackView> backpacks;

    private PendingInteraction selecting;
    /** 进入倒水阶段时冻结的现场：水要落进哪一格、那一格原来是什么、手里的水桶。 */
    private BlockPos pourCell;
    private BlockState cellBefore;
    private ItemStack bucketBefore;
    private boolean poured;

    public FallGuardTask(SurvivalSituation.SituationReader reader, Interactions interactions,
                         Function<PlayerContext, FirstPersonScene> scenes,
                         Function<PlayerContext, BackpackView> backpacks) {
        super("落地防护", Phase.TAKE_BUCKET, new ProgressTracker(20L * 30, 20L * 60));
        this.reader = reader;
        this.interactions = interactions;
        this.scenes = scenes;
        this.backpacks = backpacks;
    }

    @Override protected Action enter(Phase phase) {
        if (phase != Phase.POUR) return null;
        // 倒水：按水桶自己的射线朝那一格倒；手里的桶变空、或那一格变了，就算水倒出去了。
        return interactions.useHeldItemToward(pourCell, InteractionHand.MAIN_HAND, InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, bucketBefore),
                InteractionConfirmation.blockChanged(pourCell, cellBefore)));
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        SurvivalSituation situation = reader.read(context);
        PlayerContext player = context.player();
        if (situation == null || player == null) {
            return Next.stay();
        }
        if (!situation.falling()) {
            // 已经落地（或落进了水里、抓住了梯子）：放过水就算防护成功，没放成就如实交代。
            return poured
                    ? Next.done(TaskResult.done("落地防护：倒了水，落进水里"))
                    : Next.fail(Problem.of(Problem.Kind.DANGER, "还没来得及倒水就落地了，伤害由游戏结算"));
        }
        if (situation.landsInWater() && !poured) {
            return Next.done(TaskResult.done("落地防护：下面就是水，落进水里不用自救"));
        }
        if (situation.overVoid()) {
            return Next.fail(Problem.of(Problem.Kind.DANGER, "下面是虚空，倒水救不了这一掉"));
        }
        FirstPersonScene scene = scenes.apply(player);
        return switch (phase) {
            case TAKE_BUCKET -> takeBucket(player);
            case LOOK_DOWN -> lookDown(player, scene, situation);
            case POUR -> runActionThen(context, () -> {
                poured = true;
                recordChange(new Change(Change.Kind.BLOCK_PLACED, "minecraft:water", 1,
                        "落地放水，位置 " + pourCell.toShortString() + "，水还留在那里"));
                return Next.go(Phase.LAND, "水倒出去了");
            });
            case LAND -> Next.stay();
        };
    }

    // 水桶在手上就直接低头；在快捷栏别的格子就先切过去，等游戏确认切好了再低头。
    private Next<Phase> takeBucket(PlayerContext player) {
        if (selecting != null) {
            selecting = selecting.terminal() ? selecting : player.interactionSender().poll(player, selecting);
            if (!selecting.terminal()) return Next.stay();
            if (selecting.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
                return Next.fail(Problem.of(Problem.Kind.REFUSED_BY_GAME, "换水桶没有生效：" + selecting.detail()));
            }
            selecting = null;
        }
        BackpackView backpack = backpacks.apply(player);
        OptionalInt slot = backpack.hotbarSlotOf(WATER_BUCKET);
        if (slot.isPresent() && slot.getAsInt() == backpack.selectedHotbarSlot()) {
            return Next.go(Phase.LOOK_DOWN, "水桶在手上");
        }
        if (slot.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "这一掉会摔死，但快捷栏里没有水桶，倒不了水；落地伤害由游戏结算",
                    "身上带一桶水放在快捷栏里，高处干活时就能落地放水"));
        }
        if (player.canInteractThisTick()) {
            selecting = player.interactionSender().selectHotbar(player, slot.getAsInt(), SELECT_TIMEOUT_TICKS);
        }
        return Next.stay();
    }

    // 低头盯着落点，等落点进入够得着的距离（按水桶自己的射线看得见）就冻结现场、开始倒水。
    private Next<Phase> lookDown(PlayerContext player, FirstPersonScene scene, SurvivalSituation situation) {
        player.input().requestLook(situation.facingYaw(), 90.0f, player.clientTick());
        BlockPos cell = situation.waterCell();
        if (cell == null || scene.visibleItemHit(cell, InteractionHand.MAIN_HAND) == null) {
            return Next.stay();
        }
        pourCell = cell.immutable();
        cellBefore = scene.blockAt(pourCell);
        bucketBefore = scene.heldItem(InteractionHand.MAIN_HAND);
        return Next.go(Phase.POUR, "落点够得着了");
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case TAKE_BUCKET -> "换上水桶";
            case LOOK_DOWN -> "低头等落点够得着";
            case POUR -> "往落点倒水";
            case LAND -> "等着落进水里";
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 高速下落的半路停下来等于放弃自救；只有同时被埋这种更急的事才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
