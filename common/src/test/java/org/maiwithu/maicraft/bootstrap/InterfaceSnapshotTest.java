// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamNames;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.mcp.tool.ErrorCode;
import org.maiwithu.maicraft.mcp.tool.FieldError;
import org.maiwithu.maicraft.mcp.tool.LookupTool;
import org.maiwithu.maicraft.mcp.tool.ResultJson;
import org.maiwithu.maicraft.mcp.tool.ToolCatalog;
import org.maiwithu.maicraft.mcp.tool.ToolReply;

/**
 * 接口快照：LLM 经 MCP 看得到的东西——握手说明与五个工具的参数表、生产清单里每个能力的参数表与目标对象、
 * 参数名表与各种取值、返回与错误的样子——和仓库里存的快照逐字比较。改了就会在 diff 里看到。
 *
 * <p>能力说明的正文本来就是仓库里的资源文件（{@code assets/maicraft/abilities/*.md}），改动直接出现在 diff 里，
 * 这里不重复存。有意的改动用下面的命令重写快照，并在提交说明里写清 LLM 看到的东西变了什么：
 * {@code MAICRAFT_UPDATE_SNAPSHOTS=1 ./gradlew :common:test --tests *InterfaceSnapshotTest --rerun}
 */
class InterfaceSnapshotTest {

    /** 快照放在测试资源目录里，随代码一起提交；测试进程的工作目录是 common 模块。 */
    private static final Path SNAPSHOTS = Path.of("src", "test", "resources", "snapshots");
    private static final boolean UPDATE = "1".equals(System.getenv("MAICRAFT_UPDATE_SNAPSHOTS"));
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @TempDir
    Path tempDir;

    @Test
    void theToolsMatchTheSnapshot() throws IOException {
        JsonObject tools = new JsonObject();
        tools.addProperty("instructions", ToolCatalog.INSTRUCTIONS);
        tools.add("tools", ToolCatalog.definitions());
        check("mcp-tools.json", tools);
    }

    @Test
    void theAbilitiesMatchTheSnapshot() throws IOException {
        AbilityRegistry registry = AbilityCatalog.create(OfflineCatalog.deps(tempDir));
        LookupTool lookup = new LookupTool(registry);
        JsonObject abilities = new JsonObject();
        // 开局 lookup() 给 LLM 的一行签名清单，按登记顺序。
        abilities.add("listing", data(lookup.call(new JsonObject())));
        // 每个能力的完整条目（含不列出的），正文另有资源文件，只记它在哪。
        JsonArray details = new JsonArray();
        for (AbilityModule module : registry.all()) {
            JsonObject query = new JsonObject();
            query.addProperty("id", module.spec().id());
            JsonObject detail = data(lookup.call(query)).getAsJsonObject();
            detail.remove("doc");
            detail.addProperty("doc_resource", module.spec().doc().resourcePath());
            details.add(detail);
        }
        abilities.add("abilities", details);
        check("abilities.json", abilities);
    }

    @Test
    void theVocabularyAndReplyShapesMatchTheSnapshot() throws IOException {
        JsonObject vocabulary = new JsonObject();
        JsonObject names = new JsonObject();
        ParamNames.names().stream().sorted().forEach(name -> names.addProperty(name, ParamNames.meaning(name)));
        vocabulary.add("parameter_names", names);
        vocabulary.add("target_kinds", lowerNames(TargetKind.values()));
        vocabulary.add("towards", lowerNames(Target.Toward.values()));
        vocabulary.add("permissions", permissions());
        vocabulary.add("on_failure", lowerNames(Goal.OnFailure.values()));
        vocabulary.add("goal_run_states", lowerNames(GoalRunState.values()));
        vocabulary.add("question_reasons", lowerNames(Question.Reason.values()));
        // 问题种类在结果里照枚举原名大写写出（07 的样例与各能力说明都这样写），和别的取值不同。
        JsonArray problemKinds = new JsonArray();
        Arrays.stream(Problem.Kind.values()).forEach(kind -> problemKinds.add(kind.name()));
        vocabulary.add("problem_kinds", problemKinds);
        vocabulary.add("change_kinds", lowerNames(Change.Kind.values()));
        vocabulary.add("event_kinds", lowerNames(TaskEvent.Kind.values()));
        JsonArray codes = new JsonArray();
        Arrays.stream(ErrorCode.values()).forEach(code -> codes.add(code.wireName()));
        vocabulary.add("error_codes", codes);
        vocabulary.add("reply_shapes", replyShapes());
        check("vocabulary.json", vocabulary);
    }

    /** 许可的各个字段可以取什么，默认值是什么。 */
    private static JsonObject permissions() {
        JsonObject json = new JsonObject();
        json.add("change_blocks", lowerNames(Permissions.BlockChanges.values()));
        json.add("fight", lowerNames(Permissions.Fight.values()));
        json.add("kill_animals", lowerNames(Permissions.AnimalKilling.values()));
        json.add("survival_needs", lowerNames(Permissions.SurvivalNeeds.values()));
        Permissions defaults = Permissions.DEFAULT;
        JsonObject fallback = new JsonObject();
        fallback.addProperty("change_blocks", lower(defaults.changeBlocks()));
        fallback.addProperty("fight", lower(defaults.fight()));
        fallback.addProperty("use_rare_items", defaults.useRareItems());
        fallback.addProperty("kill_animals", lower(defaults.killAnimals()));
        fallback.addProperty("survival_needs", lower(defaults.survivalNeeds()));
        json.add("defaults", fallback);
        return json;
    }

    /** 返回、目标运行、问题、事件与错误各一份样例：字段名和取值的写法一变就看得到。 */
    private static JsonObject replyShapes() {
        TaskResult partial = TaskResult.builder(TaskResult.Status.PARTIAL, "拿到 3 块原木，还差 5 块")
                .change(new Change(Change.Kind.ITEM_GAINED, "minecraft:oak_log", 3, null))
                .unconfirmed(new Change(Change.Kind.BLOCK_BROKEN, "minecraft:oak_log", 1, "没等到服务端确认"))
                .remaining("再拿 5 块原木")
                .attempt(new Attempt("砍门口那棵树", "砍倒了"))
                .problem(Problem.of(Problem.Kind.UNREACHABLE, "剩下的树在河对岸", "换个地方找树"))
                .build();
        Question question = new Question(Question.Reason.CHOOSE_ONE, "动哪个箱子？",
                List.of(new Question.Option("b5", "门口那个"), new Question.Option("b6", "屋里那个")));
        GoalRun asking = GoalRun.fromSaved(12, Goal.of("maicraft:obtain", new Target.Here(), Params.EMPTY),
                GoalRun.NO_PARENT, -1, GoalRunState.AWAITING_ANSWER, 0, question, List.of(), List.of(), 100);
        JsonObject shapes = new JsonObject();
        shapes.add("ok", ToolReply.ok(new JsonObject(), List.of("count：\"6\" 按 6 处理"),
                ToolReply.next(ToolCatalog.EVENTS, new JsonObject())));
        shapes.add("error", ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 1 处问题",
                List.of(new FieldError("goal.parameters.count", "count 应在 1..256", "1..256")), null));
        shapes.add("result", ResultJson.result(partial));
        shapes.add("goal_run", ResultJson.goalRun(asking, question, "在等回答"));
        shapes.add("event", ResultJson.event(new TaskEvent(7, TaskEvent.Kind.ASKED, 12, "动哪个箱子？", null)));
        return shapes;
    }

    private static JsonElement data(JsonObject reply) {
        return reply.get("data");
    }

    private static JsonArray lowerNames(Enum<?>[] values) {
        JsonArray names = new JsonArray();
        for (Enum<?> value : values) names.add(lower(value));
        return names;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** 和存着的快照逐字比较；行尾不计（Windows 检出可能是 CRLF）。要求重写时直接写回。 */
    private static void check(String file, JsonElement actual) throws IOException {
        String text = PRETTY.toJson(JsonParser.parseString(actual.toString())) + "\n";
        Path stored = SNAPSHOTS.resolve(file);
        if (UPDATE) {
            Files.createDirectories(SNAPSHOTS);
            Files.writeString(stored, text, StandardCharsets.UTF_8);
            return;
        }
        String expected = Files.exists(stored) ? Files.readString(stored, StandardCharsets.UTF_8) : "";
        assertEquals(expected.replace("\r\n", "\n"), text,
                "LLM 经 MCP 看到的接口变了（" + file + "）。是有意的改动就用 MAICRAFT_UPDATE_SNAPSHOTS=1 "
                        + "./gradlew :common:test --tests *InterfaceSnapshotTest --rerun 重写快照，"
                        + "并在提交说明里写清变了什么；不是就改回来。");
    }
}
