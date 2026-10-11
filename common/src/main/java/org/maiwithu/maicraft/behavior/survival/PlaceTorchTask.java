// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.lighting.FrontLampSites;
import org.maiwithu.maicraft.behavior.lighting.LampItem;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 随行插火把的临时任务：在眼前够得着的墙或地上选一处灯位，把火把换到主手，瞄准插下去，结束。
 * 做完就结束回到主任务原地；没做成（眼前没地方、被游戏拒绝、没能确认）也如实收场，不牵连主任务。
 * 不挖洞、不垫脚、不覆盖已有方块；箱子、机器、工作台不作灯座；出手过（成不成都算）回调记下位置，
 * 太暗需求据此执行两格节制，不原地撒灯。
 */
final class PlaceTorchTask extends PhasedTask<PlaceTorchTask.Phase> {

    /** 选灯位 → 换火把到主手 → 瞄准插下。 */
    enum Phase { CHOOSE, HOLD, PLACE }

    static final String TORCH_ID = "minecraft:torch";

    /** 一次出手：插的动作与它结束后的结论读口。 */
    record Click(Action action, Supplier<InteractionResult> result) {}

    /** 出手接缝：对灯位的支撑面点一下；生产实现经瞄准交互动作，测试按脚本回结论。 */
    @FunctionalInterface
    interface Clicks {
        Click place(FrontLampSites.LampSpot spot);
    }

    private final Function<TickContext, FirstPersonScene> scenes;
    private final MovesToMainhand toMainhand;
    private final Predicate<BlockPos> opensScreen;
    private final Predicate<BlockPos> protectedAt;
    private final Consumer<BlockPos> onAttempt;
    private final Clicks clicks;
    private final LampItem torches = LampItem.torch();

    private FrontLampSites.LampSpot spot;
    private Click click;
    /** 换手动作： HOLD 阶段第一次没换完时接到本阶段逐刻推进。 */
    private Action holding;

    PlaceTorchTask(Function<TickContext, FirstPersonScene> scenes, MovesToMainhand toMainhand,
                   Predicate<BlockPos> opensScreen, Predicate<BlockPos> protectedAt,
                   Consumer<BlockPos> onAttempt, Clicks clicks) {
        super("顺手插火把", Phase.CHOOSE, new ProgressTracker(20L * 10, 20L * 30));
        this.scenes = scenes;
        this.toMainhand = toMainhand;
        this.opensScreen = opensScreen;
        this.protectedAt = protectedAt;
        this.onAttempt = onAttempt;
        this.clicks = clicks;
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            case HOLD -> holding;
            case PLACE -> click == null ? null : click.action();
            case CHOOSE -> null;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHOOSE -> choose(context);
            case HOLD -> hold(context);
            case PLACE -> runActionThen(context, this::concluded);
        };
    }

    // 眼前的灯位：视野锥里挑最近的一处；找不到地方就如实收场，太暗需求过一阵再看。
    private Next<Phase> choose(TickContext context) {
        FirstPersonScene scene = scenes.apply(context);
        var found = FrontLampSites.choose(scene, opensScreen, protectedAt,
                scene.eyePosition(), scene.viewVector(), torches);
        if (found.isEmpty()) {
            // 眼前没地方插也算出手过一回：再往前走两格才看下一回，不在原地反复停下来找。
            BlockPos feet = BlockPos.containing(scene.eyePosition().x, scene.eyePosition().y - 1.62,
                    scene.eyePosition().z);
            onAttempt.accept(feet);
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "眼前找不到能插火把的墙或地面", null));
        }
        spot = found.get();
        return Next.go(Phase.HOLD, "看中了 " + spot.lamp().toShortString() + " 这一处");
    }

    // 火把换到主手；身上没有就不插（评估时还有，现在没了也一样），已在手上就直接去插。
    private Next<Phase> hold(TickContext context) {
        if (scenes.apply(context).heldItem(InteractionHand.MAIN_HAND).is(Items.TORCH)) {
            return startPlacing();
        }
        if (action() == null) {
            holding = toMainhand.moveToMainhand(TORCH_ID).orElse(null);
            if (holding == null) {
                return Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "包里没有火把可插", null));
            }
            // 换手交给本阶段逐刻推进：重进阶段时 enter 会把它递回来。
            return Next.go(Phase.HOLD, "把火把换到主手");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
            case ActionStatus.Done done -> startPlacing();
        };
    }

    private Next<Phase> startPlacing() {
        click = clicks.place(spot);
        return Next.go(Phase.PLACE, "插在 " + spot.lamp().toShortString());
    }

    // 出手收场：成不成都算出手过，两格节制照记；插上了记变化，被拒与没能确认如实收场。
    private Next<Phase> concluded() {
        BlockPos lamp = spot.lamp();
        onAttempt.accept(lamp);
        InteractionResult result = click.result().get();
        click = null;
        if (result == null) {
            return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "插火把的交互没有给出结论", null));
        }
        switch (result.verdict()) {
            case APPLIED:
                recordChange(new Change(Change.Kind.BLOCK_PLACED, TORCH_ID, 1,
                        "顺手插在 " + lamp.toShortString()));
                return Next.done(TaskResult.done("顺手插了一支火把照亮脚下"));
            case NOT_APPLIED:
                return Next.fail(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "火把没有插上去，那一格被占住或够不着", null));
            case UNEXPECTED:
                return Next.fail(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "插下去变成了预料之外的样子：" + result.scene(), null));
            default:
                recordUnconfirmed(new Change(Change.Kind.BLOCK_PLACED, TORCH_ID, 1,
                        "插在 " + lamp.toShortString() + "，没能确认结果"));
                return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR,
                        "火把提交了但没能确认插上没有", null));
        }
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case CHOOSE -> "找眼前的灯位";
            case HOLD -> "把火把换到主手";
            case PLACE -> "插火把";
        };
    }
}
