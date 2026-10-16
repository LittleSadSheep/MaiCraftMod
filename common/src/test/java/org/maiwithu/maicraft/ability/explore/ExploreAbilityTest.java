// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.explore.ExploreObjective;
import org.maiwithu.maicraft.behavior.explore.ExploreTask;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.SimilarIds;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/** 探索能力：四种目标写法、参数互斥一次报全、认不出的 ID 不进游戏；能力本身不走路。 */
class ExploreAbilityTest {

    @TempDir
    Path temp;

    private ExploreAbility ability() {
        // 决定阶段只用到类型目录；其余协作者给无害的实例，工厂创建任务时才真正被碰到。
        return new ExploreAbility(new StubTypes(), (target, permit) -> {
            throw new AssertionError("探索能力不应自己开始走到");
        }, () -> null, new BlockScanService(),
                new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "key"),
                (kind, message) -> { });
    }

    /** 推进一刻用的决定上下文：只有目标。 */
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

    /** 按能力自己的参数规格解析参数，得到与 MCP 入口一致的取值。 */
    private ParamValues params(String json) {
        ParseResult result = ability().spec().paramSpecs()
                .parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(String paramsJson) {
        return new Goal("maicraft:explore", null, null, params(paramsJson),
                Permissions.DEFAULT, List.of(), null);
    }

    @Test
    void 自由跑图按默认半径与全向开一局() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability().decide(step(goal("{\"target\":\"survey\"}"))));
        ExploreInput input = assertInstanceOf(ExploreInput.class, run.input());
        assertEquals(ExploreObjective.Kind.SURVEY, input.objective().target());
        assertEquals(768, input.maxDistance(), "半径默认 768");
        assertEquals(360, input.angleDegrees(), "无方向全向");
        assertEquals(0, input.minDistance(), "无方向不起找距离");
        assertEquals(null, input.direction());
        assertEquals(TerrainPermit.NATURAL, input.permit(), "默认许可 natural：能挖垫");
    }

    @Test
    void 定向找群系按八向词与默认开口角起找距离() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability().decide(step(goal(
                        "{\"target\":\"minecraft:jungle\",\"direction\":\"north\"}"))));
        ExploreInput input = assertInstanceOf(ExploreInput.class, run.input());
        assertEquals(ExploreObjective.Kind.BIOME, input.objective().target());
        assertEquals(List.of("minecraft:jungle"), input.objective().selectors());
        assertEquals(Target.Toward.NORTH, input.direction());
        assertEquals(90, input.angleDegrees(), "有方向开口角默认 90");
        assertEquals(16, input.minDistance(), "有方向起找距离默认 16");
    }

    @Test
    void 找平台必配方向_回露天不用() {
        StepDecision.Finish noDirection = assertInstanceOf(StepDecision.Finish.class,
                ability().decide(step(goal("{\"target\":\"platform\"}"))));
        assertEquals(TaskResult.Status.FAILED, noDirection.result().status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, noDirection.result().problem().kind());

        StepDecision.Run withDirection = assertInstanceOf(StepDecision.Run.class,
                ability().decide(step(goal("{\"target\":\"platform\",\"direction\":\"forward\"}"))));
        assertEquals(ExploreObjective.Kind.FLAT_GROUND,
                assertInstanceOf(ExploreInput.class, withDirection.input()).objective().target());

        StepDecision.Run surface = assertInstanceOf(StepDecision.Run.class,
                ability().decide(step(goal("{\"target\":\"surface\"}"))));
        assertEquals(ExploreObjective.Kind.SURFACE,
                assertInstanceOf(ExploreInput.class, surface.input()).objective().target());
    }

    @Test
    void 参数凑不到一起的一次报全() {
        // 无方向给开口角 + 起找距离超过半径：两处一起说，不挤牙膏。
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability().decide(step(goal(
                        "{\"target\":\"surface\",\"angle_degrees\":90,\"min_distance\":300,\"max_distance\":128}"))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        String message = finish.result().problem().message();
        assertTrue(message.contains("开口角"), "无方向给开口角要报：" + message);
        assertTrue(message.contains("起找距离") || message.contains("min_distance"),
                "起找距离超半径要报：" + message);
    }

    @Test
    void 半径出界在参数规格里就拦下() {
        ParseResult result = ability().spec().paramSpecs()
                .parse(JsonParser.parseString("{\"target\":\"survey\",\"max_distance\":32}").getAsJsonObject());
        assertFalse(result.ok(), "半径 64 起步，32 应在入口就被拒");
        ParseResult tooFar = ability().spec().paramSpecs()
                .parse(JsonParser.parseString("{\"target\":\"survey\",\"max_distance\":4096}").getAsJsonObject());
        assertFalse(tooFar.ok(), "半径上限 2048，4096 应在入口就被拒");
    }

    @Test
    void 认不出的群系以不支持结束并提示先查ID() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability().decide(step(goal("{\"target\":\"minecraft:beach_shaboo\"}"))));
        assertEquals(Problem.Kind.UNSUPPORTED, finish.result().problem().kind());
        assertTrue(finish.result().problem().suggestion().contains("lookup"));
    }

    @Test
    void 群系写错时带上相近的群系并指到群系目录页() {
        TaskResult result = assertInstanceOf(StepDecision.Finish.class,
                ability().decide(step(goal("{\"target\":\"minecraft:beachh\"}")))).result();
        assertEquals("群系 minecraft:beachh 不存在；你是不是要 minecraft:beach", result.problem().message());
        assertEquals("探索：群系 minecraft:beachh 不存在", result.summary(), "一句话结论只带结论句");
        assertTrue(result.problem().suggestion().contains("lookup(topic=knowledge, id=biomes)"),
                result.problem().suggestion());
    }

    @Test
    void 留意清单写错时带上相近的名字() {
        TaskResult result = assertInstanceOf(StepDecision.Finish.class, ability().decide(step(goal(
                "{\"target\":\"survey\",\"interests\":[\"minecraft:zombi\",\"minecraft:qqq\"]}")))).result();
        assertTrue(result.problem().message().startsWith("留意清单里的 minecraft:zombi 不存在；你是不是要 minecraft:zombie；"
                + "留意清单里的 minecraft:qqq 不存在（"), result.problem().message());
    }

    @Test
    void 给了目标对象就如实拒绝() {
        // 探索没有预设终点：坐标这类已定位终点是出行的事。
        Goal request = new Goal("maicraft:explore", null, new Target.Position(12, null, -2, null),
                params("{\"target\":\"survey\"}"), Permissions.DEFAULT, List.of(), null);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability().decide(step(request)));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
    }

    @Test
    void 留意清单按注册表分类_认不出的一次报全_空数组当没给() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability().decide(step(goal("{\"target\":\"survey\",\"interests\":"
                        + "[\"lava_pool\",\"minecraft:chest\",\"#minecraft:logs\",\"minecraft:zombie\","
                        + "\"minecraft:village_plains\"]}"))));
        ExploreObjective.Interests interests = assertInstanceOf(ExploreInput.class, run.input()).interests();
        assertEquals(List.of("lava_pool"), interests.terrainKinds());
        assertEquals(List.of("minecraft:chest", "#minecraft:logs"), interests.blocks());
        assertEquals(List.of("minecraft:zombie"), interests.entities());
        assertEquals(List.of("minecraft:village_plains"), interests.structures());

        StepDecision.Finish unknown = assertInstanceOf(StepDecision.Finish.class,
                ability().decide(step(goal(
                        "{\"target\":\"survey\",\"interests\":[\"lava_pool\",\"minecraft:nonsense\"]}"))));
        assertTrue(unknown.result().problem().message().contains("minecraft:nonsense"),
                "认不出的逐个报全");

        // 空的留意清单在参数入口就按"至少要有一项"拦下：LLM 删掉这个参数就是没留意。
        ParseResult empty = ability().spec().paramSpecs()
                .parse(JsonParser.parseString("{\"target\":\"survey\",\"interests\":[]}").getAsJsonObject());
        assertFalse(empty.ok(), "空数组在入口被拦，提示删掉参数");
    }

    @Test
    void 时限与许可照实带上() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability().decide(step(goal(
                        "{\"target\":\"survey\",\"max_seconds\":120,\"interests\":[\"lava_pool\"]}"))));
        ExploreInput input = assertInstanceOf(ExploreInput.class, run.input());
        assertEquals(120, input.maxSeconds());
    }

    @Test
    void 工厂创建探索任务() {
        TaskFactories factories = new TaskFactories();
        ability().registerTasks(factories);
        ExploreInput input = new ExploreInput(ExploreObjective.survey(),
                ExploreObjective.Interests.NONE, null, 360, 0, 256, 0, TerrainPermit.NATURAL);

        assertTrue(factories.supports(ExploreInput.class));
        Task task = factories.create(input);
        assertInstanceOf(ExploreTask.class, task);
        assertNotNull(task.describe());
        assertFalse(task.describe().isBlank());
    }

    @Test
    void 能力规格是探索_不接受目标对象() {
        assertEquals("maicraft:explore", ability().spec().id());
        assertEquals(Set.of(), ability().spec().targets(), "探索不接受目标对象");
    }

    /** 类型目录替身：常见的认得出，其余一律没有；写岔时从同一份名单里挑相近的。 */
    private static final class StubTypes implements ReadsExploreTypes {
        private static final List<String> BIOMES = List.of("minecraft:jungle", "minecraft:beach",
                "minecraft:plains", "#minecraft:is_forest");
        private static final List<String> BLOCKS = List.of("minecraft:chest", "#minecraft:logs");
        private static final List<String> ENTITIES = List.of("minecraft:zombie");
        private static final List<String> STRUCTURES = List.of("minecraft:village_plains");

        @Override public boolean biomeExists(String selector) {
            return BIOMES.contains(selector.toLowerCase(Locale.ROOT));
        }

        @Override public boolean terrainExists(String selector) {
            return selector.equalsIgnoreCase("lava_pool");
        }

        @Override public boolean blockExists(String selector) {
            return BLOCKS.contains(selector.toLowerCase(Locale.ROOT));
        }

        @Override public boolean entityTypeExists(String selector) {
            return ENTITIES.contains(selector.toLowerCase(Locale.ROOT));
        }

        @Override public boolean structureExists(String selector) {
            return STRUCTURES.contains(selector.toLowerCase(Locale.ROOT));
        }

        @Override public String missing(IdKind kind, String selector) {
            return SimilarIds.missing(kind.label(), selector, SimilarIds.closest(BIOMES, selector));
        }

        @Override public Optional<String> similarInterest(String selector) {
            List<String> candidates = new ArrayList<>(BLOCKS);
            candidates.addAll(ENTITIES);
            candidates.addAll(STRUCTURES);
            return SimilarIds.closest(candidates, selector);
        }
    }
}
