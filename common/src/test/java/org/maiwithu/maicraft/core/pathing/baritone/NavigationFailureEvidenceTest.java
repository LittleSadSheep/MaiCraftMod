package org.maiwithu.maicraft.core.pathing.baritone;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;

/** 无路证据在任务清理后仍保留旧起点、真实材料与名单限制，不能变成新位置的一份空摘要。 */
public final class NavigationFailureEvidenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            w.position(new Vec3(8.5, 1, 8.5)); var target = new BlockPos(12, 1, 12);
            w.inventory.setItem(0, new ItemStack(Items.SMOOTH_STONE, 54));
            w.set(new BlockPos(8, 1, 8), Blocks.TORCH.defaultBlockState());
            w.set(new BlockPos(8, 3, 8), Blocks.SMOOTH_STONE.defaultBlockState());
            var facts = NavigationFailureEvidence.capture(w.player, target, TerrainPermit.TERRAFORM, EmbeddedBaritonePolicy.capture(null, null, null));
            check(facts.get("carried_blocks").equals(Map.of("minecraft:smooth_stone", 54)), "actual stock survives even when unavailable as scaffolding");
            check(facts.get("selected_scaffold").equals(Map.of("available", false)), "unsupported material is not counted as usable scaffolding");
            var cells = (List<?>) facts.get("origin_cells");
            check(Boolean.TRUE.equals(((Map<?, ?>) cells.get(1)).get("clearance_whitelisted"))
                    && Boolean.FALSE.equals(((Map<?, ?>) cells.get(3)).get("clearance_whitelisted")), "body torch and overhead construction have distinct clearance facts");
            w.inventory.setItem(18, new ItemStack(Items.DIRT, 64));
            var supplied = NavigationFailureEvidence.capture(w.player, target, TerrainPermit.TERRAFORM, EmbeddedBaritonePolicy.capture(null, null, null));
            check(((Map<?, ?>) supplied.get("selected_scaffold")).get("inventory_slot").equals(18), "main-inventory material is visible without a speculative swap");
            var nav = PlayerNav.toGoal(w.player, () -> NavGoal.exact(target), 1, () -> false);
            var transport = field(PlayerNav.class, "navigator").get(nav);
            var ground = field(transport.getClass(), "ground").get(transport);
            field(EmbeddedBaritoneNavigator.class, "failureEvidence").set(ground, facts);
            // 尚未推进路径时也保留调度和备料槽位，不能在默认结果或存档中洗成“搜索无路”。
            ((EmbeddedBaritoneNavigator) ground).observeDispatch(true, true, true, 9, "preparing_scaffold",
                    Map.of("source_slot", 18, "pending", true));
            var dispatch = ((EmbeddedBaritoneNavigator) ground).dispatchEvidence();
            var health = Map.of("classification", "planning_stall", "worker_stack", List.of("fixture.chunkRead"), "recovery_count", 1);
            field(EmbeddedBaritoneNavigator.class, "healthEvidence").set(ground, health);
            var task = new MoveToCompanionTask(w.player, new MoveToTaskRecord("failure-evidence", 1000, 12D, 1D, 12D, null, true));
            field(AbstractCompanionTask.class, "nav").set(task, nav);
            // 失败后角色已被人挪走，默认终态仍必须呈现导致这次失败的冻结现场。
            w.position(new Vec3(2.5, 1, 2.5));
            var result = task.result(TaskState.FAILED);
            var navigation = (Map<?, ?>) result.data().get("navigation");
            check(navigation != null && facts.equals(navigation.get("ground_failure")), "public terminal receipt retains frozen evidence after cleanup");
            var semantic = SemanticResultView.result(result);
            check(facts.equals(((Map<?, ?>) semantic.data().get("navigation")).get("ground_failure")),
                    "semantic projection retains body cells rather than treating them as an internal route");
            check(dispatch.equals(((Map<?, ?>) semantic.data().get("navigation")).get("ground_dispatch")),
                    "semantic projection retains native inventory and dispatch evidence");
            // 再经过真实持久化结果整理，保证重启后任务查询仍能看到同一份材料与起点观察。
            var safe = IntentStateCodec.class.getDeclaredMethod("safeElement", JsonElement.class); safe.setAccessible(true);
            var gson = new Gson(); var saved = (JsonElement) safe.invoke(null, gson.toJsonTree(semantic.data()));
            check(saved.getAsJsonObject().getAsJsonObject("navigation").get("ground_failure").equals(gson.toJsonTree(facts)),
                    "checkpoint serialization preserves the complete observed no-path context");
            check(saved.getAsJsonObject().getAsJsonObject("navigation").get("ground_dispatch").equals(gson.toJsonTree(dispatch)),
                    "checkpoint serialization preserves the dispatch stage and source inventory slot");
            check(saved.getAsJsonObject().getAsJsonObject("navigation").get("ground_health").equals(gson.toJsonTree(health)),
                    "search classification, recovery count and worker stack survive semantic results and checkpoints");
        }
        System.out.println("NavigationFailureEvidenceTest: passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
