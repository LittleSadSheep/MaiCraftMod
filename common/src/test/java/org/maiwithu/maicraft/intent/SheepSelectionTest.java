package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.SheepTraitsTest;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchCompanionTask;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;
import org.maiwithu.maicraft.core.task.entity.SheepTraits;
import static org.maiwithu.maicraft.client.actor.SheepTraitsTest.check;

/** 语义搜索和交互都保留同一颜色条件；探索回执如实展示实际找到的羊。 */
public final class SheepSelectionTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var white = SheepTraitsTest.sheep(world, 61, DyeColor.WHITE, 3);
            SheepTraitsTest.sheep(world, 62, DyeColor.BLACK, 2);
            for (String ability : List.of("maicraft:find_entity", "maicraft:interact")) {
                var json = JsonParser.parseString("""
                        {"outcome":"找到成年白羊","parameters":{"entity_type_id":"minecraft:sheep",
                          "sheep_color":"white","sheep_baby":false}}
                        """).getAsJsonObject();
                json.addProperty("ability", ability);
                Goal goal = Goal.fromJson(json);
                SemanticGoalContract.validate(goal, Set.of(ability));
                var action = (IntentAction.Tool) AbilityAdapter.adapt(goal, world.player, null);
                check(action.arguments().get("sheep_color").getAsString().equals("white"), "semantic handoff keeps wool color");
                if (ability.equals("maicraft:interact"))
                    check(action.arguments().get("entity_id").getAsInt() == 61, "interaction picks the white sheep despite a nearer black one");
            }
            var record = new GenericEntitySearchTaskRecord("white-search", 1000,
                    List.of(ResourceLocation.withDefaultNamespace("sheep")), GenericEntitySearchTaskRecord.Relation.ANY,
                    1, 16, false, List.of()).withSheepTraits(SheepTraits.read(JsonParser.parseString("{sheep_color:'white'}").getAsJsonObject()));
            var search = new GenericEntitySearchCompanionTask(world.player, record);
            invoke(search, "onStart"); invoke(search, "scanLoadedEntities");
            var data = (Map<?, ?>) invoke(search, "resultData");
            check(data.get("observed_acceptable_count").equals(1)
                    && ((List<?>) data.get("observed_sheep")).size() == 1, "search counts and reports only matching sheep");
            white.color = DyeColor.RED;
            invoke(search, "scanLoadedEntities");
            check(((Map<?, ?>) invoke(search, "resultData")).get("observed_acceptable_count").equals(0),
                    "a recolored sheep cannot remain in the verified search result");
        }
        System.out.println("SheepSelectionTest: passed");
    }

    private static Object invoke(Object task, String method) throws Exception {
        var entry = task.getClass().getDeclaredMethod(method); entry.setAccessible(true); return entry.invoke(task);
    }
}
