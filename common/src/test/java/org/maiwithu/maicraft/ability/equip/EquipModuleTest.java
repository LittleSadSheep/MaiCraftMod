// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsGearFit;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 装备能力做决定：手上拿什么都行、护甲栏按游戏规则、标签认身上任意一种、几样不同的才问。 */
class EquipModuleTest {

    /** 栏位匹配替身：按物品名的尾词回答天生放在哪；剑没有天生的栏位。 */
    private static final ReadsGearFit FIT = (itemId, slot) -> switch (slot) {
        case HEAD -> itemId.endsWith("helmet");
        case CHEST -> itemId.endsWith("chestplate");
        case OFFHAND -> itemId.endsWith("shield");
        default -> false;
    };

    private static final Map<String, Set<String>> TAGS = Map.of(
            "minecraft:iron_sword", Set.of("minecraft:swords"),
            "minecraft:stone_sword", Set.of("minecraft:swords"),
            "minecraft:iron_helmet", Set.of());

    @Test
    void 剑拿到主手_手上拿什么都行() {
        StepDecision decision = decide(List.of("minecraft:iron_sword"),
                "{\"operation\":\"equip\",\"slot\":\"mainhand\",\"item\":\"minecraft:iron_sword\"}");
        assertTrue(decision instanceof StepDecision.Run, decision.toString());
    }

    @Test
    void 头盔放不进胸甲栏_提交前拒绝() {
        StepDecision decision = decide(List.of("minecraft:iron_helmet"),
                "{\"operation\":\"equip\",\"slot\":\"chest\",\"item\":\"minecraft:iron_helmet\"}");
        assertProblem(decision, Problem.Kind.NOT_POSSIBLE_HERE);
    }

    @Test
    void 身上没有点名的东西_按缺物品() {
        StepDecision decision = decide(List.of(),
                "{\"operation\":\"equip\",\"slot\":\"head\",\"item\":\"minecraft:iron_helmet\"}");
        assertProblem(decision, Problem.Kind.NEED_ITEM);
    }

    @Test
    void 主手没点名_说清要拿哪样() {
        StepDecision decision = decide(List.of("minecraft:iron_sword"),
                "{\"operation\":\"equip\",\"slot\":\"mainhand\"}");
        assertProblem(decision, Problem.Kind.INVALID_PARAMETER);
    }

    @Test
    void 副手没点名_身上唯一一面盾就自动选() {
        StepDecision decision = decide(List.of("minecraft:iron_sword", "minecraft:shield"),
                "{\"operation\":\"equip\",\"slot\":\"offhand\"}");
        assertTrue(decision instanceof StepDecision.Run run
                && ((EquipInput) run.input()).itemId().equals("minecraft:shield"), decision.toString());
    }

    @Test
    void 标签认身上任意一种_几样不同的才问() {
        StepDecision one = decide(List.of("minecraft:iron_sword"),
                "{\"operation\":\"equip\",\"slot\":\"mainhand\",\"item\":\"#minecraft:swords\"}");
        assertTrue(one instanceof StepDecision.Run run
                && ((EquipInput) run.input()).itemId().equals("minecraft:iron_sword"), one.toString());
        StepDecision two = decide(List.of("minecraft:iron_sword", "minecraft:stone_sword"),
                "{\"operation\":\"equip\",\"slot\":\"mainhand\",\"item\":\"#minecraft:swords\"}");
        assertTrue(two instanceof StepDecision.Ask, two.toString());
    }

    private static void assertProblem(StepDecision decision, Problem.Kind kind) {
        assertTrue(decision instanceof StepDecision.Finish, decision.toString());
        assertEquals(kind, ((StepDecision.Finish) decision).result().problem().kind());
    }

    private static StepDecision decide(List<String> carried, String paramsJson) {
        BackpackView backpack = new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return carried.stream().map(id -> new BackpackStack(id, 1, 1, true, false, false, false)).toList();
            }

            @Override public int usedSlots() { return carried.size(); }
            @Override public int totalSlots() { return 36; }
        };
        OffhandContents offhand = Optional::empty;
        ReadsEquipment nothingWorn = slot -> Optional.empty();
        EquipModule module = new EquipModule(backpack, offhand, nothingWorn, FIT, Optional.empty(), Optional.empty(),
                itemId -> TAGS.getOrDefault(itemId, Set.of()));
        ParseResult parsed = module.spec().paramSpecs().parse(JsonParser.parseString(paramsJson).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:equip", null, null, parsed.params(), Permissions.DEFAULT, List.of(), null);
        return module.decide(new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { throw new IllegalStateException("决定阶段不碰每刻上下文"); }
        });
    }
}
