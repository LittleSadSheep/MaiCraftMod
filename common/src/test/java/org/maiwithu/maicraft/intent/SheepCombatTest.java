package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.DyeColor;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.entity.SheepTraits;
import org.maiwithu.maicraft.core.tools.CombatOps;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.SheepTraitsTest;
import java.util.Set;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.SheepTraitsTest.check;

/** 白羊在黑羊后方时仍只挑白羊；追赶中被染黑必须停手并交付变化事实。 */
public final class SheepCombatTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var white = SheepTraitsTest.sheep(world, 51, DyeColor.WHITE, 3);
            SheepTraitsTest.sheep(world, 52, DyeColor.BLACK, 2);
            Goal goal = Goal.fromJson(JsonParser.parseString("""
                    {"ability":"maicraft:combat","outcome":"只杀白羊","parameters":{
                      "entity_type_id":"minecraft:sheep","selection":"nearest",
                      "sheep_color":"white","allow_harm":true,"confirm_risky_target":true}}
                    """).getAsJsonObject());
            SemanticGoalContract.validate(goal, Set.of("maicraft:combat"));
            var action = (IntentAction.Tool) AbilityAdapter.adapt(goal, world.player, null);
            check(action.arguments().getAsJsonArray("entity_ids").get(0).getAsInt() == 51,
                    "semantic combat filters color before nearest selection");
            var record = (AttackTaskRecord) new CombatOps().attack(List.of(51), new ToolContext("white-sheep", 0), SheepTraits.read(action.arguments()));
            check(record.strictAuthorized && record.sheepTraits().matches(white), "native attack retains the color and protects non-target sheep from sweeps");
            var attack = new AttackCompanionTask(world.player, record);
            var guard = AttackCompanionTask.class.getDeclaredMethod("stopChangedSheepTargets"); guard.setAccessible(true);
            check(guard.invoke(attack) == null, "unchanged sheep remains eligible");
            white.color = DyeColor.BLACK;
            check(guard.invoke(attack) == TaskState.FAILED && record.strikes() == 0,
                    "a recolored target stops before another attack and is not counted as defeated");
            var dataMethod = AttackCompanionTask.class.getDeclaredMethod("resultData"); dataMethod.setAccessible(true);
            var data = (Map<?, ?>) dataMethod.invoke(attack);
            var changed = (List<?>) data.get("changed_sheep_targets");
            check(((Map<?, ?>) changed.getFirst()).get("sheep_color").equals("black")
                            && data.containsKey("requested_sheep_traits"), "result compares requested and actual traits");
            check(!(AbilityAdapter.adapt(goal, world.player, null) instanceof IntentAction.Tool),
                    "missing white sheep cannot silently fall back to black sheep");
            // 恢复任务仍保留原条件，不能在序列化时变回无颜色限制的攻击。
            var gson = new Gson();
            var restored = gson.fromJson(gson.toJson(record), AttackTaskRecord.class);
            check(!restored.sheepTraits().matches(white), "checkpoint retains the sheep filter");
        }
        System.out.println("SheepCombatTest: passed");
    }
}
