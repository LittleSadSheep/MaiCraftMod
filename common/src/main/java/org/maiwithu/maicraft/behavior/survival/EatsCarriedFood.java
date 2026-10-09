// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.SustainedUse;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 吃随身食物的动作来源：从背包里挑一件能直接吃的，换到主手后原生按住吃完一口。
 *
 * <p>吃饭的临时任务只管什么时候吃，怎么吃组合进食的原生动作：先像真人一样把食物换到
 * 主手（背包界面的交换），再按住使用键等游戏自己把这一口吃完。挑哪件按 {@link FoodPicker#forHunger}；
 * 身上没有这会儿该吃的东西时给不出动作，由吃饭任务去弄或如实报告"没吃的"。
 */
public final class EatsCarriedFood implements EatSoonTask.FoodMoves {

    private final BackpackView backpack;
    private final ReadsFoodValues foods;
    private final MovesToMainhand toMainhand;
    private final UseKeyProjection projection;

    public EatsCarriedFood(BackpackView backpack, ReadsFoodValues foods,
            MovesToMainhand toMainhand, UseKeyProjection projection) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.foods = Objects.requireNonNull(foods, "foods");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.projection = projection;
    }

    @Override
    public Action eating(HungerNeed.Facts hunger) {
        // 按饥饿处境挑：平时只吃补得刚好的普通食物，饱食度见底在掉血才什么都吃。
        return FoodPicker.forHunger(FoodPicker.carried(backpack.stacks(), foods), hunger.food(), hunger.losingHealth())
                .map(food -> (Action) new BiteAction(food))
                .orElse(null);
    }

    @Override
    public Action fetching(long budgetTicks) {
        // 有预算地弄吃的（翻已知容器、合成、采集）还没有实现方；给 null 让任务如实说弄不到。
        return null;
    }

    /** 吃一口的动作：换到主手 → 按住使用键；食物已经在主手就直接按住。 */
    private final class BiteAction implements Action {

        private final String food;
        /** 换手与按住两个子动作，先后接续；正在推进的那一个非空。 */
        private Action moving;
        private SustainedUse biting;
        private ItemStack heldBefore;
        /** 这一口已经换过一次手：换完主手还不是它就不反复赌，如实失败。 */
        private boolean movedOnce;
        private Problem failure;

        BiteAction(String food) {
            this.food = food;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (failure != null) return ActionStatus.failed(failure);
            if (moving != null) {
                return advanceMove(context);
            }
            if (biting == null) {
                return startBite(context);
            }
            return biting.tick(context);
        }

        // 换手推进：做成了接着按住；给不出换手动作时看主手是不是已经是它。
        private ActionStatus advanceMove(TickContext context) {
            ActionStatus status = moving.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                failure = failed.problem();
                return status;
            }
            moving = null;
            return startBite(context);
        }

        private ActionStatus startBite(TickContext context) {
            PlayerContext player = context.player();
            if (player == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME,
                        "这一刻掌握不到角色，吃不了一口", null));
            }
            ItemStack held = player.localPlayer().getMainHandItem();
            String heldId = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            // 主手已经是这件食物就直接按住；换过一次手还不是它就不再反复赌，如实失败。
            if (!heldId.equals(food)) {
                if (movedOnce) return failAgain();
                Optional<Action> move = toMainhand.moveToMainhand(food);
                if (move.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                            "身上没有能直接吃的 " + food + "，吃不了一口", null));
                }
                movedOnce = true;
                moving = move.get();
                return ActionStatus.running();
            }
            heldBefore = held.copy();
            biting = new SustainedUse(InteractionHand.MAIN_HAND,
                    InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, heldBefore),
                    projection, 0, FirstPersonScene::of);
            return ActionStatus.running();
        }

        // 换手做成了主手却还不是它：可能被别的流程动了手，这一口如实失败，由吃饭任务决定要不要再试。
        private ActionStatus failAgain() {
            failure = Problem.of(Problem.Kind.STUCK,
                    "把 " + food + " 换到主手后主手却不是它，吃不了一口", null);
            return ActionStatus.failed(failure);
        }

        @Override
        public String describe() {
            return "吃一件随身食物";
        }
    }
}
