// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.StepsAside;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 丢弃能力：丢掉指定数量的某种东西。丢是明确指令，也是不可逆的——所以数量必填、
 * 范围在参数规格里卡死，"丢弃不可逆"写进能力说明让下达的人想清楚。
 * 请求量超过实际持有时按实际持有的丢，partial 结束写清差额，不提问；
 * 身上一件都没有时以缺东西结束。真的去丢，由任务在"丢出去、走开、登记避让"里做。
 */
public final class DropModule implements AbilityModule {

    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ReadsCharacterPosition position;
    private final DropAvoidance avoidance;
    private final Optional<MovesToMainhand> toMainhand;
    private final Optional<StepsAside> stepsAside;

    /**
     * @param backpack    背包视图：实际持有多少从这里数
     * @param offhand     副手内容：副手上的也算持有的
     * @param tags        物品标签：按标签点名时匹配用
     * @param position    角色位置：登记丢弃落点、走开都以它为基准
     * @param avoidance   本会话的丢弃落点避让：丢过的落点寻路绕开
     * @param toMainhand  换到主手的接缝；没接上传 {@code Optional.empty()}
     * @param stepsAside  走开几步的接缝；没接上传 {@code Optional.empty()}
     */
    public DropModule(BackpackView backpack, OffhandContents offhand, ReadsItemTags tags,
            ReadsCharacterPosition position, DropAvoidance avoidance,
            Optional<MovesToMainhand> toMainhand, Optional<StepsAside> stepsAside) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.position = Objects.requireNonNull(position, "position");
        this.avoidance = Objects.requireNonNull(avoidance, "avoidance");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.stepsAside = Objects.requireNonNull(stepsAside, "stepsAside");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec("maicraft:drop", "把身上带的东西丢出去",
                AbilityDoc.forAbility("drop"),
                ParamSpecs.of(
                        ParamSpec.of("item", ParamType.ITEM_OR_TAG).required()
                                .doc("要丢的物品 ID").build(),
                        ParamSpec.of("count", ParamType.INTEGER).required().range(1, 999)
                                .doc("要丢几件；超过实际持有时会按实际持有的丢").build()),
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        String item = params.text("item");
        int count = (int) params.integer("count");
        // 数量以执行时的实际持有为准：接单到执行之间世界在变，这里只挡"一件都没有"。
        int carried = CarriedItems.matching(backpack, offhand,
                new ItemRequest(new WantedItem(item), count, "丢弃"), tags);
        if (carried <= 0) {
            return new StepDecision.Finish(TaskResult.failed("丢不了：身上没有 " + item,
                    Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + item, null)));
        }
        // 给的是标签：丢身上挂着这个标签、数量最多的那一种；丢的动作一次只认一种具体的物品。
        return new StepDecision.Run(new DropInput(item.startsWith("#") ? mostCarriedIn(item.substring(1)) : item,
                count));
    }

    private String mostCarriedIn(String tagId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var stack : backpack.stacks()) {
            if (tags.tagsOf(stack.itemId()).contains(tagId)) counts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        offhand.heldInOffhand()
                .filter(stack -> tags.tagsOf(stack.itemId()).contains(tagId))
                .ifPresent(stack -> counts.merge(stack.itemId(), stack.count(), Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 任务需要的接缝在这里闭包交给它；换手与走开的接缝没接上时任务按现状如实结算。
        factories.register(DropInput.class, input -> new DropTask(input, backpack, offhand,
                position, FirstPersonScene::of, toMainhand, stepsAside, avoidance));
    }
}
