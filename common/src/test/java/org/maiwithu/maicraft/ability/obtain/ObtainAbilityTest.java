// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.StartsAcquisition;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 拿东西的能力决定：参数在游戏里立不住时一次报全、不进世界；立得住时给出带限定的任务输入。
 * 注册表用替身，不碰游戏。
 */
class ObtainAbilityTest {

    /** 替身：登记了哪些物品与标签全写死在这里。 */
    private record FakeRegistry() implements ReadsItemRegistry {
        @Override public boolean itemExists(String itemId) {
            return itemId.equals("minecraft:torch") || itemId.equals("minecraft:coal");
        }

        @Override public boolean tagHasItems(String tagId) {
            return tagId.equals("minecraft:logs");
        }
    }

    /** 替身：不该被调用的引擎；被调了就报错，说明校验没拦住。 */
    private record UselessAcquisition() implements StartsAcquisition {
        @Override public Action need(ItemRequest request, Permissions permissions,
                ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
            throw new IllegalStateException("参数校验该拦下的请求到了引擎手里");
        }
    }

    private ObtainAbility ability() {
        return new ObtainAbility(new UselessAcquisition(),
                List.of(AcquireVia.CRAFT, AcquireVia.SMELT, AcquireVia.CONTAINER,
                        AcquireVia.MINE, AcquireVia.HARVEST, AcquireVia.TRADE),
                new FakeRegistry(), emptyBackpack(), null, itemId -> Set.of());
    }

    private StepDecision decide(JsonObject params) {
        ParseResult parsed = ability().spec().paramSpecs().parse(params);
        if (parsed.params() == null) {
            throw new IllegalArgumentException("测试参数没过参数规格：" + parsed.errors());
        }
        Goal goal = Goal.of("maicraft:obtain", null, parsed.params());
        return ability().decide(new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return null;
            }
        });
    }

    @Test
    void 不存在的物品ID进不了世界() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:toch");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        TaskResult result = finish.result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().message().contains("minecraft:toch"));
    }

    @Test
    void 空标签进不了世界() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "#minecraft:nope");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("#minecraft:nope"));
    }

    @Test
    void 合法参数给出带限定的任务输入() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:torch");
        params.addProperty("count", 8);
        params.addProperty("via", "craft");
        params.addProperty("radius", 16);
        params.addProperty("max_distance", 40);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(params));
        ObtainItems input = assertInstanceOf(ObtainItems.class, run.input());
        assertEquals("minecraft:torch", input.wanted().specifier());
        assertEquals(8, input.count());
        assertEquals(Set.of("craft"), input.scope().vias());
        assertEquals(Double.valueOf(40), input.scope().maxDistanceBlocks());
        assertEquals(Integer.valueOf(16), input.scope().radiusBlocks());
    }

    @Test
    void 半径超出防误扫上限被参数规格拦下() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:torch");
        params.addProperty("radius", 500);
        assertNull(ability().spec().paramSpecs().parse(params).params(), "超出上限的半径不该通过校验");
    }

    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of();
            }

            @Override public int usedSlots() {
                return 0;
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
    }
}
