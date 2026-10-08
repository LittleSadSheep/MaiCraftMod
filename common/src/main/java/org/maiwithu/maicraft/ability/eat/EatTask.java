// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.eat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.SustainedUse;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsHunger;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

import net.minecraft.world.InteractionHand;

/**
 * 吃东西的任务：把挑好的食物换到主手，原生按住吃完一件件数完为止。
 *
 * <p>确认条件是"同类物品总量减少"：吃进肚子会让身上（主背包加副手）这种食物变少。
 * 确认窗口里新捡到同一种会抬高总数，单看结束时的数量会漏掉吃掉的——按住期间记住见过的
 * 最低数量，只要低过开始时的数量就证明真的吃了，捡回来的干扰自然被扣除。
 * 吃完一件再吃下一件；吃出来的状态效果用吃之前和之后身上效果列表的差如实记录。
 * 数量没少就不冒充吃饱：带着当前的饥饿值失败。
 */
final class EatTask extends PhasedTask<EatTask.Phase> {

    /** 使用通道点下去多久没动静就按没吃上收尾；再叠加食物本身的进食时长与收尾余量。 */
    private static final int MIN_HOLD_TICKS = 40;

    /** 任务的进度：换到主手 → 吃（一件一次按住）。 */
    enum Phase { TO_HAND, EATING }

    private final EatInput input;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsHunger hunger;
    private final ReadsEquipment equipment;
    private final ReadsEffects effects;
    private final ReadsFoodValues foods;
    private final UseKeyProjection projection;
    private final Optional<MovesToMainhand> toMainhand;
    private final Function<PlayerContext, FirstPersonScene> scenes;

    /** 本件开始时身上有几件：确认条件以它为基准。 */
    private int baseline;
    /** 本件按住期间见过的最低数量；低于基准就证明吃进去了。 */
    private int lowestSeen;
    /** 吃之前身上的状态效果，吃完做差得到吃出来的效果。 */
    private List<String> effectsBefore = List.of();
    /** 已确认吃掉的件数。 */
    private int eaten;
    /** 吃出来的状态效果（跨件累计）。 */
    private final List<String> effectsGained = new ArrayList<>();

    EatTask(EatInput input, BackpackView backpack, OffhandContents offhand, ReadsHunger hunger,
            ReadsEquipment equipment, ReadsEffects effects, ReadsFoodValues foods,
            UseKeyProjection projection, Optional<MovesToMainhand> toMainhand,
            Function<PlayerContext, FirstPersonScene> scenes) {
        super("吃东西", Phase.TO_HAND, new ProgressTracker(100, 20L * 60 * Math.max(1, input.count()) + 400));
        this.input = input;
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.hunger = Objects.requireNonNull(hunger, "hunger");
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.foods = Objects.requireNonNull(foods, "foods");
        this.projection = projection;
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    @Override
    protected Action enter(Phase phase) {
        if (phase == Phase.TO_HAND) {
            if (mainhandMatches()) return null;
            return toMainhand.flatMap(moves -> moves.moveToMainhand(input.itemId())).orElse(null);
        }
        // 吃每一件前重新清点基准：上一件已经让总数减一，确认条件要跟着新基准走。
        baseline = carried();
        lowestSeen = baseline;
        effectsBefore = effects.active();
        int holdTicks = holdTicks();
        return new SustainedUse(InteractionHand.MAIN_HAND, this::consumed,
                projection, holdTicks, scenes);
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (phase == Phase.TO_HAND) {
            if (mainhandMatches()) return Next.go(Phase.EATING, "食物已在主手上");
            if (action() == null) {
                return Next.fail(new Problem(Problem.Kind.UNSUPPORTED,
                        "把" + input.itemId() + "换到主手的界面操作还没接上，吃不了", null));
            }
            return runActionThen(context, () -> Next.go(Phase.EATING, "换到主手了"));
        }
        if (unitsLeft() <= 0) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                            "吃完了 " + eaten + " 件 " + input.itemId())
                    .details(new EatDetails(List.copyOf(effectsGained))).build());
        }
        // 吃着吃着饱了：普通食物吃不下剩下的，如实按做成的一部分结束。
        if (hunger.hungerMechanicsOn() && hunger.foodLevel() >= 20 && !edibleWhenFull()) {
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            "吃了 " + eaten + " 件 " + input.itemId() + "，已经饱了，剩下的吃不下")
                    .remaining("再吃 " + unitsLeft() + " 件 " + input.itemId())
                    .details(new EatDetails(List.copyOf(effectsGained))).build());
        }
        if (carried() <= 0) {
            return Next.fail(new Problem(Problem.Kind.NEED_ITEM,
                    "身上已经没有" + input.itemId() + "了，还差 " + unitsLeft() + " 件没吃", null));
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> afterOneEaten();
            case ActionStatus.Failed failed -> failWithHunger(failed.problem());
        };
    }

    // 一件吃完了：按确认窗口里的实际扣减记账，吃出来的效果做差累计，还有件数就再来一件。
    private Next<Phase> afterOneEaten() {
        int after = carried();
        // 拾取干扰可能把总数顶回基准之上：按见过的最低值算这一件确实少了多少。
        int consumed = Math.max(1, baseline - Math.min(after, baseline));
        eaten += consumed;
        recordChange(new Change(Change.Kind.ITEM_CONSUMED, input.itemId(), consumed, null));
        for (String effect : effects.active()) {
            if (!effectsBefore.contains(effect) && !effectsGained.contains(effect)) {
                effectsGained.add(effect);
            }
        }
        if (unitsLeft() > 0 && carried() > 0) {
            return Next.go(Phase.EATING, "再吃一件");
        }
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "吃完了 " + eaten + " 件 " + input.itemId())
                .details(new EatDetails(List.copyOf(effectsGained))).build());
    }

    // 使用结束了数量没少：不冒充吃饱，带着当前的饥饿值如实失败。
    private Next<Phase> failWithHunger(Problem cause) {
        String hungerNow = hunger.hungerMechanicsOn()
                ? "，当前饱食度 " + hunger.foodLevel() + "/20" : "，创造模式没有饥饿机制";
        return Next.fail(new Problem(cause.kind(),
                "没能吃上" + input.itemId() + "：" + cause.message() + hungerNow, cause.suggestion()));
    }

    // 确认条件：按住期间身上这种食物只要低过基准，就证明吃进去了一口以上。
    private InteractionConfirmation.Verdict consumed(PlayerContext context) {
        lowestSeen = Math.min(lowestSeen, carried());
        return lowestSeen < baseline
                ? InteractionConfirmation.Verdict.APPLIED
                : InteractionConfirmation.Verdict.PENDING;
    }

    private int unitsLeft() {
        return input.count() - eaten;
    }

    private boolean mainhandMatches() {
        return equipment.slot(GearSlotName.MAINHAND)
                .map(stack -> stack.itemId().equals(input.itemId()))
                .orElse(false);
    }

    private boolean edibleWhenFull() {
        return foods.of(input.itemId()).map(ReadsFoodValues.FoodValue::edibleWhenFull).orElse(false);
    }

    private int holdTicks() {
        int eatTicks = foods.of(input.itemId())
                .map(value -> (int) Math.ceil(value.eatSeconds() * 20) + MIN_HOLD_TICKS)
                .orElse(MIN_HOLD_TICKS);
        return Math.max(MIN_HOLD_TICKS, eatTicks);
    }

    // 身上（主背包加副手）这种食物的总数：进食的确认只认这里数出来的。
    private int carried() {
        int total = 0;
        for (var stack : backpack.stacks()) {
            if (stack.itemId().equals(input.itemId())) total += stack.count();
        }
        if (offhand != null) {
            total += offhand.heldInOffhand()
                    .filter(stack -> stack.itemId().equals(input.itemId()))
                    .map(stack -> stack.count())
                    .orElse(0);
        }
        return total;
    }

    /** 吃出来的状态效果；没效果为空列表。 */
    record EatDetails(List<String> effectsGained) implements ResultDetails {
        public EatDetails {
            effectsGained = List.copyOf(effectsGained);
        }
    }
}
