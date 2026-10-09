// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四个接上行为的工具：lookup、execute、task、events，以及调度时把异常换成错误种类。 */
class ToolsTest {

    /** 测试用的本刻上下文：只有刻号。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    private final ToolTestAbility use = ToolTestAbility.use();
    private final ToolTestAbility remember = ToolTestAbility.remember();
    private final ControlLoop loop = new ControlLoop(List.of());
    private final TaskEventLog events = new TaskEventLog();
    private final List<String> remembered = new ArrayList<>();
    private long tick = 100;
    private boolean inWorld = true;
    private final ToolDispatcher tools;

    ToolsTest() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(use);
        registry.register(ToolTestAbility.sleep());
        registry.register(remember);
        registry.register(ToolTestAbility.hidden());
        // 控制权交接给空实现：工具测试不接输入层。
        GoalRunTable table = new GoalRunTable(registry,
                new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), events),
                (name, position) -> remembered.add(name), loop, new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() {
                        return true;
                    }

                    @Override public void requestControl() {
                    }
                });
        ClientThread direct = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                if (!inWorld) throw new NotInWorld();
                return work.apply(new Tick(tick));
            }
        };
        tools = new ToolDispatcher(List.of(new LookupTool(registry, KnowledgeLibrary.offline()), new ExecuteTool(registry, table, direct),
                new TaskTool(table, direct), new EventsTool(events, table, direct)));
    }

    private JsonObject call(String tool, String arguments) {
        return tools.call(tool, JsonParser.parseString(arguments).getAsJsonObject());
    }

    private static JsonObject data(JsonObject reply) {
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("data");
    }

    private static String errorCode(JsonObject reply) {
        assertFalse(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("error").get("code").getAsString();
    }

    private void runTicks(int count) {
        for (int i = 0; i < count; i++) loop.tick(new Tick(tick++));
    }

    @Test
    void lookupListsSignaturesOfListedAbilitiesOnly() {
        JsonArray abilities = data(call("lookup", "{}")).getAsJsonArray("abilities");

        assertEquals(3, abilities.size());
        JsonObject first = abilities.get(0).getAsJsonObject();
        assertEquals("maicraft:use", first.get("ability").getAsString());
        assertEquals("item?: string, count: integer = 1", first.get("parameters").getAsString());
        assertEquals("[\"position\",\"seen\"]", first.get("targets").toString());
    }

    @Test
    void lookupByIdGivesTheParameterTableAndFindsHiddenAbilities() {
        JsonObject detail = data(call("lookup", "{\"id\": \"use\"}"));
        JsonObject hidden = data(call("lookup", "{\"id\": \"maicraft:debug_probe\"}"));

        assertEquals(2, detail.getAsJsonArray("parameters").size());
        assertEquals("read_only", hidden.get("mode").getAsString());
        assertEquals("unknown_ability", errorCode(call("lookup", "{\"id\": \"sleeep\"}")));
        assertEquals("invalid_parameter", errorCode(call("lookup", "{\"topic\": \"wiki\"}")));
    }

    @Test
    void lookupKnowledgeListsFindsAndReadsTheBundledMechanics() {
        String oreHeights = "maicraft://knowledge/game_mechanics/ore-heights";
        // 目录列出随包的常识，每篇有地址、标题和一句话说明；索引那一篇不列，目录本身就是索引。
        List<String> listed = new ArrayList<>();
        for (var row : data(call("lookup", "{\"topic\": \"knowledge\"}")).getAsJsonArray("knowledge")) {
            JsonObject entry = row.getAsJsonObject();
            assertFalse(entry.get("summary").getAsString().isBlank(), "每篇都有一句话说明");
            listed.add(entry.get("id").getAsString());
        }
        assertTrue(listed.contains(oreHeights));
        assertFalse(listed.contains("maicraft://knowledge/index"));

        // 不带空格的一句问话也能找到：条目的检索词出现在问句里就算命中，并建议读排在最前的那篇。
        JsonObject found = call("lookup", "{\"topic\": \"knowledge\", \"query\": \"钻石在哪一层\"}");
        assertEquals(oreHeights, data(found).getAsJsonArray("knowledge").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(oreHeights, found.getAsJsonObject("next").getAsJsonObject("arguments").get("id").getAsString());

        // 末段名就能读，按哪篇读的写进 notes；没有这篇时如实说编号不存在，不拿相近的顶替。
        JsonObject read = call("lookup", "{\"topic\": \"knowledge\", \"id\": \"food\"}");
        assertEquals("maicraft://knowledge/game_mechanics/food", data(read).get("id").getAsString());
        assertTrue(data(read).get("text").getAsString().contains("饥饿"));
        assertTrue(read.has("notes"));
        assertEquals("unknown_id", errorCode(call("lookup", "{\"topic\": \"knowledge\", \"id\": \"no-such-page\"}")));

        // 没找到不等于没有这条规则：给空列表，并建议列出完整目录。
        JsonObject none = call("lookup", "{\"topic\": \"knowledge\", \"query\": \"zzzz\"}");
        assertEquals(0, data(none).getAsJsonArray("knowledge").size());
        assertFalse(none.getAsJsonObject("next").getAsJsonObject("arguments").has("id"));
    }

    @Test
    void executeReportsEveryMistakeAndStartsGoodGoals() {
        assertEquals("invalid_parameter", errorCode(call("execute",
                "{\"goal\": {\"ability\": \"maicraft:use\", \"parameters\": {\"count\": 0}}}")));

        JsonObject reply = call("execute", "{\"goal\": {\"ability\": \"use\"}, \"request_key\": \"k1\"}");
        JsonObject started = data(reply);
        JsonObject again = data(call("execute", "{\"goal\": {\"ability\": \"use\"}, \"request_key\": \"k1\"}"));

        assertEquals(started.get("task_id"), again.get("task_id"));
        assertTrue(again.get("repeated").getAsBoolean());
        assertEquals("events", reply.getAsJsonObject("next").get("tool").getAsString());
    }

    @Test
    void dryRunGivesAPlanThatRunsOnce() {
        String planId = data(call("execute", "{\"goal\": {\"ability\": \"sleep\"}, \"dry_run\": true}"))
                .get("plan_id").getAsString();

        data(call("execute", "{\"plan_id\": \"" + planId + "\"}"));
        assertEquals("unknown_id", errorCode(call("execute", "{\"plan_id\": \"" + planId + "\"}")));
    }

    @Test
    void memoryOnlyGoalsFinishOnTheSpotWithoutReplacingTheMainTask() {
        data(call("execute", "{\"goal\": {\"ability\": \"use\"}}"));
        Object mainTask = loop.currentTask();
        remember.decision = new StepDecision.Finish(TaskResult.done("记住了"));

        JsonObject result = data(call("execute",
                "{\"goal\": {\"ability\": \"remember\", \"parameters\": {\"name\": \"家\"}}}"));

        assertEquals("finished", result.get("state").getAsString());
        assertEquals("done", result.getAsJsonObject("result").get("status").getAsString());
        assertEquals(mainTask, loop.currentTask());
    }

    @Test
    void taskAnswersMustPickAnOfferedOption() {
        use.decision = new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE, "用哪个？",
                List.of(new Question.Option("b5", "门口的"), new Question.Option("b6", "屋里的"))));
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("task_id").getAsLong();
        runTicks(2);

        JsonObject view = data(call("task", "{\"operation\": \"get\", \"task_id\": " + id + "}"));
        assertEquals("awaiting_answer", view.get("state").getAsString());
        assertEquals("b5", view.getAsJsonObject("question").getAsJsonArray("options").get(0)
                .getAsJsonObject().get("id").getAsString());
        assertEquals("invalid_parameter", errorCode(call("task",
                "{\"operation\": \"answer\", \"task_id\": " + id + ", \"answer\": \"b9\"}")));
        assertEquals("running", data(call("task",
                "{\"operation\": \"answer\", \"task_id\": " + id + ", \"answer\": \"b6\"}")).get("state").getAsString());
    }

    @Test
    void taskPausesResumesCancelsAndReportsUnknownIds() {
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("task_id").getAsLong();

        assertEquals("paused", data(call("task", "{\"operation\": \"pause\", \"task_id\": " + id + "}"))
                .get("state").getAsString());
        assertEquals("running", data(call("task", "{\"operation\": \"resume\", \"task_id\": \"" + id + "\"}"))
                .get("state").getAsString());
        JsonObject cancelled = data(call("task", "{\"operation\": \"cancel\", \"task_id\": " + id + "}"));
        assertEquals("cancelled", cancelled.getAsJsonObject("result").get("status").getAsString());
        assertEquals(1, data(call("task", "{\"operation\": \"list\"}")).getAsJsonArray("tasks").size());
        assertEquals("unknown_id", errorCode(call("task", "{\"operation\": \"get\", \"task_id\": 999}")));
    }

    @Test
    void eventsFollowAGoalAndTellTheCallerHowToContinue() {
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("task_id").getAsLong();
        runTicks(2);

        JsonObject reply = call("events", "{\"task_id\": " + id + "}");
        JsonObject page = data(reply);
        List<String> kinds = new ArrayList<>();
        page.getAsJsonArray("events").forEach(event -> kinds.add(event.getAsJsonObject().get("kind").getAsString()));

        assertEquals(List.of("started", "finished"), kinds);
        assertEquals("done", page.getAsJsonObject("task").getAsJsonObject("result").get("status").getAsString());
        JsonObject next = reply.getAsJsonObject("next").getAsJsonObject("arguments");
        assertEquals(page.get("cursor"), next.get("after_cursor"));
        assertEquals(page.get("stream_id"), next.get("stream_id"));
    }

    @Test
    void outsideTheWorldToolsSaySoInsteadOfFailingBlindly() {
        inWorld = false;

        assertEquals("not_in_world", errorCode(call("execute", "{\"goal\": {\"ability\": \"use\"}}")));
        assertTrue(data(call("lookup", "{}")).has("abilities"));
    }
}
