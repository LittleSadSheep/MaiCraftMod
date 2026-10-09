// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsGearFit;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 装备能力：穿装备、卸装备。参数在这里先核一遍——operation 缺了、unequip 不给栏位、
 * equip 给了 armor，都不进游戏直接拒绝；equip 永远一次一件，unequip 允许 armor 整套卸。
 * 没点名穿哪件时，身上候选唯一就自动选，几种不同型的向 LLM 问穿哪个（后果不同）。
 */
public final class EquipModule implements AbilityModule {

    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsEquipment equipment;
    private final ReadsGearFit fit;
    private final Optional<InventorySpace> space;
    private final Optional<GearChanges> gear;
    private final ReadsItemTags tags;

    /**
     * @param backpack  背包视图：穿哪件的候选、卸下后多出来的东西都从这里看
     * @param offhand   副手内容：副手也是装备的候选来源
     * @param equipment 装备栏视图：现在穿着什么、穿没穿上看它
     * @param fit       物品与栏位的匹配规则：头盔进不了胸甲栏
     * @param space     腾背包的模型：卸下要有地方放；没接上传 {@code Optional.empty()}
     * @param gear      穿卸装备的执行接缝；没接上传 {@code Optional.empty()}
     * @param tags      物品挂着哪些标签：item 给标签时按它认身上的东西
     */
    public EquipModule(BackpackView backpack, OffhandContents offhand, ReadsEquipment equipment,
            ReadsGearFit fit, Optional<InventorySpace> space, Optional<GearChanges> gear, ReadsItemTags tags) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.fit = Objects.requireNonNull(fit, "fit");
        this.space = Objects.requireNonNull(space, "space");
        this.gear = Objects.requireNonNull(gear, "gear");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec("maicraft:equip", "穿装备或卸装备",
                AbilityDoc.forAbility("equip"),
                ParamSpec.of(
                        Param.of("operation", ParamType.CHOICE).required().choices("equip", "unequip")
                                .doc("equip 穿上 / unequip 卸下").build(),
                        Param.of("slot", ParamType.CHOICE)
                                .choices("mainhand", "offhand", "head", "chest", "legs", "feet", "armor")
                                .doc("目标栏位；armor 只配合 unequip，表示整套护甲卸下").build(),
                        Param.of("item", ParamType.ITEM_OR_TAG)
                                .doc("要穿的物品 ID 或标签；护甲栏候选唯一时可以不给，手上拿什么得点名").build()),
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        if (!params.has("operation")) {
            return reject("缺 operation：equip 穿上 / unequip 卸下");
        }
        String operation = params.text("operation");
        if (!params.has("slot")) {
            return reject("缺 slot：要说明动哪个栏位（mainhand、offhand、head、chest、legs、feet"
                    + (operation.equals("unequip") ? "、armor 整套卸下" : "") + "）");
        }
        String slotText = params.text("slot");
        if (slotText.equals("armor")) {
            if (operation.equals("equip")) {
                return reject("equip 永远一次一件：slot 不能是 armor，请指明具体的护甲栏位");
            }
            return new StepDecision.Run(new EquipInput(true, null, null));
        }
        GearSlotName slot = GearSlotName.fromParam(slotText);
        if (slot == null) {
            return reject("slot 取值不对：" + slotText);
        }
        if (operation.equals("unequip")) {
            return decideUnequip(slot);
        }
        return decideEquip(step, slot);
    }

    // 卸下：栏位本来就是空的就直接完成；armor 整套卸在任务里逐格来。
    private StepDecision decideUnequip(GearSlotName slot) {
        if (equipment.slot(slot).isEmpty()) {
            return new StepDecision.Finish(TaskResult.done(
                    "开始时 " + slot.paramName() + " 就是空的，已经卸下了"));
        }
        return new StepDecision.Run(new EquipInput(true, slot, null));
    }

    // 穿上：先定穿哪件，再核对栏位，最后看是不是已经穿着同一件。
    // 手上（主手、副手）拿什么都行；护甲栏按游戏规则只收对应的护甲。标签表示其中任意一种。
    private StepDecision decideEquip(StepContext step, GearSlotName slot) {
        String named = step.goal().params().has("item")
                ? step.goal().params().text("item").toLowerCase(Locale.ROOT) : null;
        boolean hand = slot == GearSlotName.MAINHAND || slot == GearSlotName.OFFHAND;
        List<String> candidates;
        if (named == null) {
            candidates = GearCandidates.fitting(backpack, offhand, slot, fit);
            if (candidates.isEmpty()) {
                // 手上拿什么都行：没点名又没有天生放那里的东西（盾牌之于副手），说不清拿哪样。
                return hand ? reject("没点名 item：" + slot.paramName() + " 上拿什么都行，要说清拿哪样")
                        : new StepDecision.Finish(TaskResult.failed(
                                "穿不上：身上没有能放进 " + slot.paramName() + " 的东西",
                                Problem.of(Problem.Kind.NEED_ITEM, "身上没有能放进 " + slot.paramName() + " 的装备", null)));
            }
        } else {
            candidates = GearCandidates.carriedMatching(backpack, offhand, named, tags).stream()
                    .filter(itemId -> hand || fit.fits(itemId, slot))
                    .toList();
            if (candidates.isEmpty()) {
                return missingNamed(named, slot, hand);
            }
        }
        if (candidates.size() > 1) {
            return chooseAmong(step, slot, candidates);
        }
        String itemId = candidates.getFirst();
        // 目标栏位已经是同一件（按物品类型比，不看耐久与附魔）：直接完成。
        boolean worn = equipment.slot(slot)
                .map(stack -> stack.itemId().equals(itemId))
                .orElse(false);
        if (worn) {
            return new StepDecision.Finish(TaskResult.done(
                    slot.paramName() + " 上已经是 " + itemId + "，不用再穿"));
        }
        return new StepDecision.Run(new EquipInput(false, slot, itemId));
    }

    // 点名的东西身上没有就是缺；身上有却放不进这个护甲栏，按游戏规则如实说放不进，不进游戏。
    private StepDecision missingNamed(String named, GearSlotName slot, boolean hand) {
        if (!hand && !GearCandidates.carriedMatching(backpack, offhand, named, tags).isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed(
                    named + " 放不进 " + slot.paramName() + "：物品类型和栏位不匹配",
                    Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, named + " 按游戏规则放不进 " + slot.paramName(), null)));
        }
        return new StepDecision.Finish(TaskResult.failed("穿不上：身上没有 " + named,
                Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + named, null)));
    }

    // 候选是几种不同的物品：穿哪个后果不同，问 LLM 选一种；问过就按回答办。
    private StepDecision chooseAmong(StepContext step, GearSlotName slot, List<String> candidates) {
        List<Question.Option> options = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            options.add(new Question.Option(String.valueOf(i + 1),
                    "把 " + candidates.get(i) + " 穿到 " + slot.paramName()));
        }
        if (!step.answers().isEmpty()) {
            String answer = step.answers().get(step.answers().size() - 1).trim();
            int picked = -1;
            for (int i = 0; i < candidates.size(); i++) {
                if (String.valueOf(i + 1).equals(answer) || candidates.get(i).equals(answer)) {
                    picked = i;
                    break;
                }
            }
            if (picked < 0) {
                return new StepDecision.Finish(TaskResult.cancelled(
                        "没有从候选里选出一个：" + answer + "，这次不换了"));
            }
            return new StepDecision.Run(new EquipInput(false, slot, candidates.get(picked)));
        }
        return new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE,
                "身上有好几样能放进 " + slot.paramName() + " 的东西，穿哪个？",
                options));
    }

    // 参数凑不到一起：不进游戏，一次说清哪里不对。
    private StepDecision reject(String message) {
        return new StepDecision.Finish(TaskResult.failed("参数不对：" + message,
                Problem.of(Problem.Kind.INVALID_PARAMETER, message, null)));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(EquipInput.class, input -> new EquipTask(input, equipment, space, gear));
    }
}
