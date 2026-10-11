// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 落地防护临时任务：意外掉下去、预计会掉血时，就地找落点（正下方或周围两格内空中推得过去的，水优先），
 * 用身上带着的水桶、蛛网、浆果、藤蔓、黏液块或干草（或落点现成的水源、蛛网）接住自己，落地站稳后收回自己放的。
 *
 * <p>顺序：找落点 → 接住（边掉边把东西拿到手上、往落点推、够得着时只放一次、落地站稳、收回）。
 * 落点本来就是水不用做事；下面是虚空救不了；身上没东西、附近也没现成的就如实说，落地伤害由游戏结算。
 * 救命不看任务许可；收不回的东西留在原地，结果里写清楚。
 */
public final class FallGuardTask extends PhasedTask<FallGuardTask.Phase> {

    public enum Phase { FIND, CATCH }

    private final SurvivalSituation.SituationReader reader;
    /** 就地找落点与办法，交给一次空中救援；救不了为 null。 */
    private final Function<PlayerContext, CatchesFall> rescues;
    private CatchesFall rescue;

    public FallGuardTask(SurvivalSituation.SituationReader reader, Function<PlayerContext, CatchesFall> rescues) {
        super("落地防护", Phase.FIND, new ProgressTracker(20L * 30, 20L * 60));
        this.reader = reader;
        this.rescues = rescues;
    }

    @Override
    protected Action enter(Phase phase) {
        return phase == Phase.CATCH ? new CatchFall(rescue) : null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (phase == Phase.CATCH) return runActionThen(context, this::concluded);
        SurvivalSituation situation = reader.read(context);
        PlayerContext player = context.player();
        if (situation == null || player == null) return Next.stay();
        if (!situation.falling()) return Next.done(TaskResult.done("落地防护：已经没在往下掉了"));
        if (situation.landsInWater()) return Next.done(TaskResult.done("落地防护：下面就是水，落进水里不用自救"));
        if (situation.overVoid()) {
            return Next.fail(Problem.of(Problem.Kind.DANGER, "下面是虚空，什么都接不住这一掉"));
        }
        rescue = rescues.apply(player);
        if (rescue == null) {
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "这一掉会掉血，可身上没有能接住自己的东西（水桶、干草、黏液块、蛛网……），落点附近也没有现成的水；落地伤害由游戏结算",
                    "高处干活时身上带一桶水（或干草、黏液块），掉下去时就能接住自己"));
        }
        return Next.go(Phase.CATCH, "用" + rescue.itemId() + "接住，落点 " + rescue.landingFeet().toShortString());
    }

    // 接住这一掉收场：留在原地没收回的格子记进变化；救没救成按救援自己的认定。
    private Next<Phase> concluded() {
        List<BlockPos> leftInPlace = new ArrayList<>();
        for (CatchesFall.Change change : rescue.drainChanges()) {
            if (!leftInPlace.remove(change.position())) leftInPlace.add(change.position());
        }
        for (BlockPos cell : leftInPlace) {
            recordChange(new Change(Change.Kind.BLOCK_PLACED, rescue.itemId(), 1,
                    "落地救援放在 " + cell.toShortString() + "，没收回，还留在那里"));
        }
        if (rescue.failed()) return Next.fail(Problem.of(Problem.Kind.DANGER, rescue.detail()));
        return Next.done(TaskResult.done("落地防护：" + rescue.detail()));
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "找能接住自己的落点";
            case CATCH -> rescue == null ? "接住这一掉" : "接住这一掉：" + rescue.detail();
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 高速下落的半路停下来等于放弃自救；只有同时被埋这种更急的事才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }

    /** 接住这一掉的动作：每刻推进救援、按它的意思往落点推；被打断时救援收手（没确认的放置交还、打开的背包关上）。 */
    private static final class CatchFall implements Action {
        private final CatchesFall rescue;
        private PlayerContext last;

        CatchFall(CatchesFall rescue) {
            this.rescue = rescue;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            last = player;
            rescue.tick(player);
            if (rescue.complete()) return ActionStatus.done();
            player.input().applySteering(yaw -> rescue.steering(player, yaw),
                    player.localPlayer().getYRot(), player.clientTick());
            return ActionStatus.running();
        }

        @Override
        public void close() {
            if (!rescue.complete() && last != null) rescue.stop(last, "落地防护被打断");
        }

        @Override
        public Interruptibility interruptibility() {
            return Interruptibility.UNSAFE_TO_STOP;
        }

        @Override
        public String describe() {
            return "接住这一掉：" + rescue.detail();
        }
    }
}
