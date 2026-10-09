// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.eat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.survival.FoodPicker;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsHunger;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 进食能力：饿了吃东西。看现场挑食物、决定现在吃不吃得下，把具体的吃交给任务。
 *
 * <p>这是能力的全部知识：拍板过的规则都在这里——不预判"吃了有没有用"，
 * 带效果的食物照吃、效果如实写进结果；饱腹时普通食物直接以时间不对结束，不提问；
 * 创造模式没有饥饿机制，同样直接结束。
 */
public final class EatModule implements AbilityModule {

    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsHunger hunger;
    private final ReadsFoodValues foods;
    private final ReadsEquipment equipment;
    private final ReadsEffects effects;
    private final ReadsItemTags tags;
    private final UseKeyProjection projection;
    private final Optional<MovesToMainhand> toMainhand;

    /**
     * @param backpack    背包视图：身上有什么从这里读
     * @param offhand     副手内容：副手上的食物也是身上的
     * @param hunger      饥饿情况：饱不饱、有没有饥饿机制
     * @param foods       食物数值：挑哪种吃、饱腹时能不能吃
     * @param equipment   装备栏视图：食物在不在主手
     * @param effects     状态效果：吃之前之后做差
     * @param tags        物品标签：按标签点名食物时匹配用
     * @param projection  按住使用键投影：进食的按住续期靠它
     * @param toMainhand  换到主手的接缝；没接上传 {@code Optional.empty()}
     */
    public EatModule(BackpackView backpack, OffhandContents offhand, ReadsHunger hunger,
            ReadsFoodValues foods, ReadsEquipment equipment, ReadsEffects effects,
            ReadsItemTags tags, UseKeyProjection projection, Optional<MovesToMainhand> toMainhand) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.hunger = Objects.requireNonNull(hunger, "hunger");
        this.foods = Objects.requireNonNull(foods, "foods");
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec("maicraft:eat", "吃身上带的食物",
                AbilityDoc.forAbility("eat"),
                ParamSpec.of(
                        Param.of("item", ParamType.ITEM_OR_TAG)
                                .doc("要吃的物品 ID；不给时只在普通食物里挑，没有再吃腐肉这类垃圾食物，金苹果、紫颂果、河豚要点名").build(),
                        Param.of("count", ParamType.INTEGER).range(1, 64).defaultValue(1)
                                .doc("吃几件").build()),
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        String named = goal.params().has("item") ? goal.params().text("item") : null;
        int count = (int) goal.params().integer("count");

        // 创造模式没有饥饿机制：吃普通食物不消耗，直接以时间不对结束，不进游戏。
        if (!hunger.hungerMechanicsOn()) {
            return asFailure(Problem.Kind.WRONG_TIME, "创造模式没有饥饿机制，不用吃东西");
        }

        List<FoodPicker.Carried> carried = carriedFoods();
        if (named != null) {
            return decideNamed(carried, named, count);
        }
        // 没点名而且已经饱了：这件事本来就不用做，写明开始时已饱直接完成。
        if (hunger.foodLevel() >= 20) {
            return new StepDecision.Finish(TaskResult.done(
                    "开始时已经饱了（饱食度 " + hunger.foodLevel() + "/20），没有点名要吃的东西"));
        }
        Optional<String> picked = FoodPicker.autoPick(carried);
        if (picked.isEmpty() && carried.isEmpty()) {
            return asFailure(Problem.Kind.NEED_ITEM, "身上没有能吃的东西");
        }
        if (picked.isEmpty()) {
            // "随便吃点"不包括金苹果、紫颂果、河豚这类：身上只剩它们时写清有哪些，点名了才吃。
            String onHand = carried.stream().map(food -> food.itemId() + " ×" + food.count())
                    .collect(Collectors.joining("、"));
            return new StepDecision.Finish(TaskResult.failed("没有吃东西：身上只有要点名才吃的食物（" + onHand + "）",
                    Problem.of(Problem.Kind.NEED_ITEM, "身上没有普通食物，只有珍贵的、会传送的或会中毒的：" + onHand,
                            "确实要吃就点名 item")));
        }
        return new StepDecision.Run(new EatInput(picked.get(), count));
    }

    // 点名了要吃哪种：只有这一种能选。找不到、或它根本不是食物，都直接结束不提问。
    private StepDecision decideNamed(List<FoodPicker.Carried> carried, String named, int count) {
        if (foods.of(named).isEmpty() && !named.startsWith("#")) {
            return asFailure(Problem.Kind.UNSUPPORTED, named + " 不是能吃的东西；要用它请下 use");
        }
        Optional<String> found = FoodPicker.named(carried, named, tags);
        if (found.isEmpty()) {
            return asFailure(Problem.Kind.NEED_ITEM, "身上没有 " + named);
        }
        String itemId = found.get();
        // 饱食度已满且这种食物不带"饱腹也能吃"的组件：原版就不让开始吃，直接结束。
        if (hunger.foodLevel() >= 20 && !edibleWhenFull(itemId)) {
            return asFailure(Problem.Kind.WRONG_TIME, "已经饱了（饱食度 " + hunger.foodLevel()
                    + "/20），" + itemId + " 饱腹时吃不下");
        }
        return new StepDecision.Run(new EatInput(itemId, count));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(EatInput.class, input ->
                new EatTask(input, backpack, offhand, hunger, equipment, effects, foods,
                        projection, toMainhand, FirstPersonScene::of));
    }

    private boolean edibleWhenFull(String itemId) {
        return foods.of(itemId).map(ReadsFoodValues.FoodValue::edibleWhenFull).orElse(false);
    }

    // 身上（主背包加副手）带着的能吃的：数量累计，数值问游戏的食物组件。
    private List<FoodPicker.Carried> carriedFoods() {
        List<BackpackStack> stacks = new ArrayList<>(backpack.stacks());
        offhand.heldInOffhand().ifPresent(stacks::add);
        return FoodPicker.carried(stacks, foods);
    }

    /** 直接结束的失败：问题种类写明是缺食物、时间不对，还是不支持。 */
    private StepDecision.Finish asFailure(Problem.Kind kind, String message) {
        return new StepDecision.Finish(TaskResult.failed("没有吃东西：" + message,
                Problem.of(kind, message, null)));
    }
}
