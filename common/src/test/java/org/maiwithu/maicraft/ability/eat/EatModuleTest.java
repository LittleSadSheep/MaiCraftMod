// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.eat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsFoodValues.FoodEffect;
import org.maiwithu.maicraft.game.player.ReadsFoodValues.FoodValue;
import org.maiwithu.maicraft.game.player.ReadsHunger;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 没点名就是"随便吃点"：只剩金苹果、河豚这类要点名才吃的，按缺东西结束并写清身上有哪些。 */
class EatModuleTest {

    private static final Map<String, FoodValue> FOODS = Map.of(
            "minecraft:golden_apple", new FoodValue("minecraft:golden_apple", 4, 9.6f, 1.6f, true,
                    List.of(new FoodEffect("minecraft:regeneration", true))),
            "minecraft:pufferfish", new FoodValue("minecraft:pufferfish", 1, 0.2f, 1.6f, false,
                    List.of(new FoodEffect("minecraft:poison", false))),
            "minecraft:rotten_flesh", new FoodValue("minecraft:rotten_flesh", 4, 0.8f, 1.6f, false,
                    List.of(new FoodEffect("minecraft:hunger", false))));

    @Test
    void onlyFoodsThatMustBeNamedEndsAsNeedItemListingThem() {
        StepDecision decision = module(stack("minecraft:golden_apple", 1), stack("minecraft:pufferfish", 2))
                .decide(step(unnamed()));

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        Problem problem = finish.result().problem();
        assertEquals(Problem.Kind.NEED_ITEM, problem.kind());
        assertTrue(problem.message().contains("minecraft:golden_apple") && problem.message().contains("minecraft:pufferfish"),
                problem.message());
    }

    @Test
    void junkFoodIsEatenWhenNoPlainFoodIsCarried() {
        StepDecision decision = module(stack("minecraft:golden_apple", 1), stack("minecraft:rotten_flesh", 3))
                .decide(step(unnamed()));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decision);
        assertEquals("minecraft:rotten_flesh", assertInstanceOf(EatInput.class, run.input()).itemId());
    }

    private static BackpackStack stack(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, true, false, false);
    }

    private static EatModule module(BackpackStack... stacks) {
        BackpackView backpack = new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of(stacks);
            }

            @Override public int usedSlots() {
                return stacks.length;
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
        ReadsHunger hunger = new ReadsHunger() {
            @Override public int foodLevel() {
                return 10;
            }

            @Override public boolean hungerMechanicsOn() {
                return true;
            }
        };
        ReadsFoodValues foods = itemId -> Optional.ofNullable(FOODS.get(itemId));
        UseKeyProjection projection = new UseKeyProjection() {
            @Override public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                    InteractionHand hand, ItemStack before) {
                return false;
            }

            @Override public void release(Object owner) {}
        };
        return new EatModule(backpack, Optional::empty, hunger, foods, slot -> Optional.empty(), List::of,
                itemId -> Set.of(), projection, Optional.empty());
    }

    private static Goal unnamed() {
        ParseResult parsed = module().spec().params().parse(new JsonObject());
        assertTrue(parsed.ok(), "不给参数也能解析：" + parsed.errors());
        return new Goal("maicraft:eat", null, null, parsed.params(), null, List.of(), null);
    }

    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }
}
