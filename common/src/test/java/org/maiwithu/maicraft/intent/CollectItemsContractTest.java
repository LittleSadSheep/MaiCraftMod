package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest;
import org.maiwithu.maicraft.core.scan.DroppedItemObservation;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 公开目标使用观察引用完成编译；任务展示和存档完整保留所选物品组件，拼错筛选不能扩大拾取范围。 */
public final class CollectItemsContractTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var drop = ItemEntityReceiptsTest.item(world, 200, new Vec3(4.5, 1, 3.5), new ItemStack(Items.DIAMOND, 3));
            var observation = DroppedItemObservation.describe(world.player, drop);
            var parameters = new JsonObject(); parameters.add("drop_ref", observation.get("drop_ref"));
            Goal goal = goal(parameters);
            SemanticGoalContract.validate(goal, IntentRuntime.KNOWN_ABILITIES);
            var action = (IntentAction.Native) GeneralAbilityAdapter.adapt(goal, world.player, null);
            var record = (CollectItemsTaskRecord) action.record();
            check(record.targetUuids.equals(Set.of(drop.getUUID())), "MCP 目标必须传递观察选中的身份");
            // 组件中可能有槽位和深层数据；这些是已观察内容，展示及重启不能按内部执行字段删掉。
            var components = new JsonObject(); components.addProperty("slots", "物品内容".repeat(1500));
            observation.add("components", components);
            Map<String, Object> facts = Map.of("drop_collection", Map.of("observations", List.of(observation)));
            var receipt = TaskResult.ok("collected selected drop", facts);
            var visible = SemanticResultView.result(receipt);
            check(visible.toJson().contains("物品内容".repeat(1500)), "公开结果保留完整组件");
            var task = new IntentTaskRecord(UUID.randomUUID(), null, goal); task.terminal(TaskState.SUCCESS, visible, 1);
            var restored = IntentStateCodec.decode(IntentStateCodec.encode("drops", List.of(), List.of(task), Map.of(), List.of()));
            check(restored.tasks().getFirst().terminal().resultJson().contains("物品内容".repeat(1500))
                    && restored.tasks().getFirst().goal().parameters().equals(parameters), "持久化保留组件和原选择引用");
            for (String invalid : List.of("{\"item_ids\":[]}", "{\"item_ids\":[\"missing:item\"]}",
                    "{\"item_ids\":[false]}", "{\"drop_ref\":\"bad\"}", "{\"drop_ref\":null}",
                    "{\"radius\":0}", "{\"radius\":1.5}", "{\"radius\":\"16\"}")) {
                try { SemanticGoalContract.validate(goal(JsonParser.parseString(invalid).getAsJsonObject()), IntentRuntime.KNOWN_ABILITIES);
                    throw new AssertionError("invalid selection accepted: " + invalid); }
                catch (IllegalArgumentException expected) { }
            }
            parameters.addProperty("drop_ref", "minecraft:the_nether|" + drop.getUUID());
            try { GeneralAbilityAdapter.adapt(goal(parameters), world.player, null); throw new AssertionError("cross-dimension selection accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("CollectItemsContractTest: passed");
    }
    private static Goal goal(JsonObject parameters) {
        return new Goal(GeneralAbilityAdapter.COLLECT, "捡起选中的物品", null, parameters.toString(), "{}", List.of(), List.of());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
