// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord.Purpose;
import org.maiwithu.maicraft.core.tools.work.SemanticBlockSearchApi;
import org.maiwithu.maicraft.task.TaskState;

/** 显式用途必须贯穿目标适配、原生任务与默认回执，不能只把“一格岩浆”改名成“一个合适的池子”。 */
public final class PortalLavaPoolSearchTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var goal = new Goal(GeneralAbilityAdapter.FIND_BLOCK, "寻找适合浇筑的岩浆池", null,
                    "{\"purpose\":\"portal_casting\",\"count\":2}", "{}", List.of(), List.of());
            var action = (IntentAction.Tool) AbilityAdapter.adapt(goal, h.player, null);
            var compiled = JsonParser.parseString(action.argumentsJson()).getAsJsonObject();
            check(compiled.get("purpose").getAsString().equals("portal_casting")
                    && compiled.getAsJsonArray("block_ids").get(0).getAsString().equals("minecraft:lava"),
                    "the public purpose defaults the selector and reaches the tool");
            var context = new ToolContext("casting-pool-api", 0);
            check(SemanticBlockSearchApi.newRecord(context, null, 1, 16, "portal_casting").purpose == Purpose.PORTAL_CASTING,
                    "the typed task preserves the purpose even when selectors were omitted");
            for (String purpose : List.of("unknown", "portal_casting")) {
                try {
                    SemanticBlockSearchApi.newRecord(context, List.of("minecraft:stone"), 1, 16, purpose);
                    throw new AssertionError("invalid purpose or non-lava selectors must not start a search");
                } catch (IllegalArgumentException expected) { /* 参数冲突在只读任务提交前明确报告。 */ }
            }
        }
        search("isolated", "blocks", 1, TaskState.SUCCESS, 1);
        search("isolated", "portal_casting", 1, TaskState.FAILED, 0);
        search("separate_small", "portal_casting", 1, TaskState.FAILED, 0);
        search("large", "portal_casting", 1, TaskState.SUCCESS, 1);
        search("large", "portal_casting", 2, TaskState.FAILED, 1);
        System.out.println("PortalLavaPoolSearchTest: explicit purpose, pool counts and honest absence passed");
    }

    private static void search(String scene, String purpose, int requested, TaskState expected, int matches) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(7.5, 3, .5));
            h.set(new BlockPos(1, 1, 1), Blocks.LAVA.defaultBlockState());
            // 一个大池与两个小池分别摆放；两个十二格小池总量虽多，也不能拼成同一个可浇筑池。
            if (scene.equals("large")) for (int x = 3; x <= 8; x++) for (int z = 3; z <= 7; z++)
                h.set(new BlockPos(x, 1, z), Blocks.LAVA.defaultBlockState());
            if (scene.equals("separate_small")) for (int start : new int[]{3, 10})
                for (int x = start; x < start + 3; x++) for (int z = 3; z <= 6; z++)
                    h.set(new BlockPos(x, 1, z), Blocks.LAVA.defaultBlockState());
            var record = SemanticBlockSearchApi.newRecord(new ToolContext("casting-pool-" + scene, 0),
                    List.of("minecraft:lava"), requested, 16, purpose);
            var task = new SemanticBlockSearchCompanionTask(h.player, record);
            task.start(h.player);
            var state = TaskState.RUNNING;
            for (int tick = 0; tick < 256 && state == TaskState.RUNNING; tick++) {
                h.nextTick(); state = task.tick(h.player);
            }
            var data = task.result(state).data();
            check(state == expected && data.get("verified").equals(expected == TaskState.SUCCESS), "search success follows the requested purpose");
            check(data.get("observed_acceptable_count").equals(matches), "count measures matching pools rather than individual sources");
            if (purpose.equals("portal_casting")) {
                check(data.get("count_unit").equals("casting_lava_pools"), "the receipt declares the changed count unit");
                var survey = (Map<?, ?>) data.get("lava_pool_survey");
                check(survey.get("matching_portal_casting_pools").equals(matches), "the full pool report agrees with the terminal count");
                if (expected == TaskState.FAILED) check(data.get("failure_code").equals(matches == 0
                        ? "no_suitable_lava_pool_within_bound" : "insufficient_casting_pool_count"), "absence and partial pool matches stay distinct");
            }
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
