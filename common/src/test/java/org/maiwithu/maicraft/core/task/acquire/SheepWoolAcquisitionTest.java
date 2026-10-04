package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.SheepTraitsTest;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import static org.maiwithu.maicraft.client.actor.SheepTraitsTest.check;

/** 取白色羊毛时，距离更近的黑羊、幼羊和已剪毛羊都不能成为狩猎候选。 */
public final class SheepWoolAcquisitionTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var white = SheepTraitsTest.sheep(world, 81, DyeColor.WHITE, 5);
            SheepTraitsTest.sheep(world, 82, DyeColor.BLACK, 2);
            SheepTraitsTest.sheep(world, 83, DyeColor.WHITE, 3).young = true;
            SheepTraitsTest.sheep(world, 84, DyeColor.WHITE, 4).shorn = true;
            var wool = ResourceLocation.withDefaultNamespace("white_wool");
            var source = new SemanticAcquireTaskRecord.SourceHint(List.of(),
                    List.of(ResourceLocation.withDefaultNamespace("sheep")), List.of(wool), List.of(), "white sheep wool");
            var record = new SemanticAcquireTaskRecord("white-wool", 1000, List.of(wool), 1,
                    List.of(SemanticAcquireTaskRecord.Source.HUNT), true, source, List.of(), 16);
            var task = new SemanticAcquireCompanionTask(world.player, record);
            var need = new AcquisitionNeed(List.of(wool), 1, 0, Set.of(), Set.of(), Set.of(), record.allowedSources);
            var select = SemanticAcquireCompanionTask.class.getDeclaredMethod("safeLoadedHuntCandidates", AcquisitionNeed.class,
                    SemanticAcquireTaskRecord.SourceHint.class, GenericEntitySearchTaskRecord.Relation.class, int.class, List.class);
            select.setAccessible(true);
            check(((List<?>) select.invoke(task, need, source, GenericEntitySearchTaskRecord.Relation.WILD, 16, new ArrayList<>())).equals(List.of(white)),
                    "wool hunt uses the requested color, age and shearing state before choosing by distance");
            white.color = DyeColor.BLACK;
            check(((List<?>) select.invoke(task, need, source, GenericEntitySearchTaskRecord.Relation.WILD, 16, new ArrayList<>())).isEmpty(),
                    "the same hunt selector used during retargeting cannot choose a wrong-colored sheep");
            // 变色事实要穿过父任务的回执整理，不能只留在内部攻击子任务。
            var activeSource = task.getClass().getDeclaredField("activeSource"); activeSource.setAccessible(true);
            activeSource.set(task, SemanticAcquireTaskRecord.Source.HUNT);
            var activeRecord = task.getClass().getDeclaredField("activeRecord"); activeRecord.setAccessible(true);
            activeRecord.set(task, new AttackTaskRecord("wool-child", 1000, List.of(81), false));
            var receipt = Map.of("requested_sheep_traits", Map.of("sheep_colors", List.of("white")),
                    "changed_sheep_targets", List.of(Map.of("sheep_color", "black")));
            var summarize = task.getClass().getDeclaredMethod("childDataForAttempt", Map.class); summarize.setAccessible(true);
            var delivered = (Map<?, ?>) summarize.invoke(task, receipt);
            check(receipt.entrySet().stream().allMatch(entry -> entry.getValue().equals(delivered.get(entry.getKey()))),
                    "acquisition receipt retains requested and observed sheep traits");
        }
        System.out.println("SheepWoolAcquisitionTest: passed");
    }
}
