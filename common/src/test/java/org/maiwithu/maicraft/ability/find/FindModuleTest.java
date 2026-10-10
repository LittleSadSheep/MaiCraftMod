// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 寻找能力：block/blocks、entity、structure 三选一，写岔的 ID 一次报全，再凑成一次寻找任务。 */
class FindModuleTest {

    /** 游戏类型目录替身：只认这几个 ID 与标签。 */
    private static ReadsWorldTypes types() {
        return new ReadsWorldTypes() {
            @Override public boolean blockTypeExists(String blockIdOrTag) {
                return Set.of("minecraft:coal_ore", "minecraft:chest", "#minecraft:logs").contains(blockIdOrTag);
            }

            @Override public boolean entityTypeExists(String entityTypeId) {
                return Set.of("minecraft:zombie", "minecraft:sheep").contains(entityTypeId);
            }
        };
    }

    /** 世界记忆替身：寻找做决定时不写记忆，够用就行。 */
    private static final RemembersSightings NO_MEMORY = new RemembersSightings() {
        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) { }
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) { }
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) { }
    };

    @TempDir
    Path temp;

    private FindModule module() {
        Scene scene = new Scene(NO_MEMORY, new FacilityKinds(FakeBlockTags.vanilla()));
        return new FindModule(types(), input -> new FindsAround.Round(List.of(), true, false),
                scene, NO_MEMORY);
    }

    /** 推进一刻用的决定上下文：只有目标。 */
    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { throw new IllegalStateException("决定阶段不应碰到每刻上下文"); }
        };
    }

    /** 按能力自己的参数规格解析参数，得到与 MCP 入口一致的取值。 */
    private ParamValues params(String json) {
        ParseResult result = module().spec().paramSpecs().parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(String paramsJson) {
        return new Goal("maicraft:find", null, null, params(paramsJson), Permissions.DEFAULT, List.of(), null);
    }

    @Test
    void specIsFindWithThreeWayChoiceAndCount() {
        assertEquals("maicraft:find", module().spec().id());
        assertEquals(1L, params("{}").integer("count"), "count 默认 1");
    }

    @Test
    void decideRunsFindTaskWithKindDefaults() {
        FindInput input = runInput(goal("{\"block\": \"minecraft:coal_ore\"}"));
        assertEquals(FindInput.FindKind.BLOCK, input.kind());
        assertEquals(1, input.count());
        assertEquals(48, input.radius(), "方块缺省半径 48 格");

        FindInput entity = runInput(goal("{\"entity\": [\"minecraft:zombie\", \"minecraft:sheep\"]}"));
        assertEquals(FindInput.FindKind.ENTITY, entity.kind());
        assertEquals(2, entity.selectors().size());
        assertEquals(64, entity.radius(), "实体缺省半径 64 格");

        FindInput structure = runInput(goal("{\"structure\": \"minecraft:village_plains\"}"));
        assertEquals(FindInput.FindKind.STRUCTURE, structure.kind());
        assertEquals(List.of("minecraft:village_plains"), structure.selectors());
    }

    @Test
    void decideCarriesCountAndRadiusOverrides() {
        FindInput input = runInput(goal("{\"block\": \"minecraft:chest\", \"count\": 5, \"radius\": 16}"));
        assertEquals(5, input.count());
        assertEquals(16, input.radius());
    }

    @Test
    void decideFailsWhenSelectorsAreMissingOrDoubled() {
        assertEquals(Problem.Kind.INVALID_PARAMETER, problemOf(goal("{}")).kind(),
                "什么都不给就不是一次寻找");
        assertEquals(Problem.Kind.INVALID_PARAMETER,
                problemOf(goal("{\"block\": \"minecraft:chest\", \"entity\": [\"minecraft:sheep\"]}")).kind(),
                "两种一起给说不清找什么");
    }

    @Test
    void decideRejectsUnknownIdsAllAtOnce() {
        TaskResult result = failResult(goal("{\"blocks\": [\"minecraft:nope\", \"minecraft:alsonot\"]}"));
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().message().contains("minecraft:nope")
                && result.problem().message().contains("minecraft:alsonot"),
                "写岔的 ID 一次报全：" + result.problem().message());

        TaskResult entity = failResult(goal("{\"entity\": [\"minecraft:creeper\"]}"));
        assertEquals(Problem.Kind.INVALID_PARAMETER, entity.problem().kind());
    }

    private FindInput runInput(Goal goal) {
        StepDecision decision = module().decide(step(goal));
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decision);
        return assertInstanceOf(FindInput.class, run.input());
    }

    private Problem problemOf(Goal goal) {
        return failResult(goal).problem();
    }

    private TaskResult failResult(Goal goal) {
        StepDecision decision = module().decide(step(goal));
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        return finish.result();
    }

    @Test
    void singleEntityStringIsReadAsOneItem() {
        FindInput input = runInput(goal("{\"entity\": \"minecraft:zombie\"}"));
        assertEquals(List.of("minecraft:zombie"), input.selectors(), "单个字符串按一项的列表处理");
    }
}
