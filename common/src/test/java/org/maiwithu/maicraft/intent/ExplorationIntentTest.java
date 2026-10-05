package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.tools.work.SemanticExploreApi;

/** 公开目标经适配和任务保存后仍保留方向与角度，沙滩不再依赖海洋判据。 */
public final class ExplorationIntentTest {
    public static void main(String[] args) {
        var args1 = tool("maicraft:explore", "{\"biome_id\":\"modded:autumn_forest\",\"direction\":\"west\",\"angle_degrees\":120}");
        check(args1.toolName().equals("explore") && args1.arguments().get("angle_degrees").getAsInt() == 120,
                "retain modded biome and sector");
        check(tool("maicraft:explore", "{\"direction\":\"north\"}").arguments().get("target").getAsString().equals("survey"),
                "direction-only survey does not invent a target biome");
        var travel = tool("maicraft:travel", "{\"biome_tag\":\"minecraft:is_forest\",\"direction\":\"north\"}");
        check(travel.arguments().get("direction").getAsString().equals("north"), "travel forwards discovery direction");
        var structure = tool("maicraft:explore", "{\"structure_id\":\"minecraft:village\",\"direction\":\"east\",\"transport_mode\":\"ground\"}");
        check(structure.toolName().equals("structure_search") && structure.arguments().has("direction"), "structure search retains sector");
        var record = SemanticExploreApi.newRecord(new ToolContext("coast", 0), "coast", 128, false, "ground", "north", 60, 32);
        check(record.target.equals("minecraft:beach") && record.sector.angleDegrees() == 60 && record.sector.minDistance() == 32,
                "coast alias and sector survive task creation");
        var interestRecord = SemanticExploreApi.newRecord(
                new ToolContext("survey", 0), "survey", 128, false, "auto", null, null, null, List.of("lava_pool"));
        check(interestRecord.interests().equals(List.of("lava_pool")), "interests survive task creation");
        // 与目标选择器可并存：声明兴趣的探索目标照常适配并透传。
        var withInterests = tool("maicraft:explore", "{\"biome_id\":\"modded:autumn_forest\",\"interests\":[\"lava_pool\"]}");
        check(withInterests.arguments().getAsJsonArray("interests").size() == 1
                && withInterests.arguments().getAsJsonArray("interests").get(0).getAsString().equals("lava_pool"),
                "declared interests pass the contract and reach the tool arguments");
        rejects("{\"biome_id\":\"minecraft:beach\",\"structure_id\":\"minecraft:village\"}");
        rejects("{\"angle_degrees\":90}");
        rejects("{\"direction\":\"north\",\"angle_degrees\":15.5}");
        rejects("{\"direction\":\"north\",\"max_distance\":64,\"min_distance\":128}");
        rejectsWithWhitelistMessage("{\"interests\":[\"diamond_ore\"]}");
        rejectsWithWhitelistMessage("{\"interests\":[]}");
        rejectsWithWhitelistMessage("{\"interests\":\"lava_pool\"}");
        System.out.println("ExplorationIntentTest: passed");
    }
    private static IntentAction.Tool tool(String ability, String parameters) {
        var goal = new Goal(ability, "探索指定地区", null, parameters, "{}", List.of(), List.of());
        SemanticGoalContract.validate(goal, IntentRuntime.KNOWN_ABILITIES);
        return (IntentAction.Tool) AbilityAdapter.adapt(goal, null, null);
    }
    private static void rejects(String parameters) {
        try { tool("maicraft:explore", parameters); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid exploration accepted: " + JsonParser.parseString(parameters));
    }
    /** 兴趣白名单的报错必须列出合法值，模型改写时不需要再查一次目录。 */
    private static void rejectsWithWhitelistMessage(String parameters) {
        try {
            tool("maicraft:explore", parameters);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("lava_pool"),
                    "interest rejection lists the legal values: " + expected.getMessage());
            return;
        }
        throw new AssertionError("invalid interests accepted: " + JsonParser.parseString(parameters));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
