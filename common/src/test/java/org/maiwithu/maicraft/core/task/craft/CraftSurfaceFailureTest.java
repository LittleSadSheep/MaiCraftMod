package org.maiwithu.maicraft.core.task.craft;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 工作台子施工缺食物与真正站位失败必须分开，不能在同一身体门槛下遍历整个场地。 */
public final class CraftSurfaceFailureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario("build_food_unavailable", false, false, true);
        scenario("placement_unconfirmed", true, false, true);
        scenario("placement_stances_exhausted", false, false, false);
        scenario("placement_stances_exhausted", false, true, false);
        System.out.println("CraftSurfaceFailureTest: passed");
    }
    private static void scenario(String code, boolean uncertain, boolean existing, boolean stops) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); var at = new BlockPos(5, 1, 5);
            if (existing) h.set(at, Blocks.CRAFTING_TABLE.defaultBlockState());
            var task = new CraftCompanionTask(h.player, new CraftTaskRecord("surface", 1000, ResourceLocation.parse("minecraft:paper"), 3, null));
            var child = new Failure(code, uncertain);
            set(task, "surfaceChild", child);
            set(task, "surfaceRecord", new BuildTaskRecord("place", 1000, List.of(new BuildTaskRecord.Target(
                    Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, at, "table", null, null, null)), true));
            set(task, "surfaceDirective", new CraftingWorkstationCoordinator.Directive(
                    CraftingWorkstationCoordinator.Action.PLACE_CARRIED, at, Blocks.CRAFTING_TABLE, "place carried table"));
            var tick = CraftCompanionTask.class.getDeclaredMethod("tickSurfaceChild"); tick.setAccessible(true);
            var state = (TaskState) tick.invoke(task);
            var sites = (Set<?>) get(get(task, "workstation"), "rejectedSites");
            check(state == (stops ? TaskState.FAILED : TaskState.RUNNING) && child.ticks == 1, "global failures end after one child result");
            check(stops ? sites.isEmpty() : existing || sites.contains(at.asLong()), "only geometric rejection may choose another site");
            var result = task.result(stops ? TaskState.FAILED : TaskState.CANCELLED).data();
            if (stops) check(result.containsKey("workstation_placement_failure")
                    && Boolean.FALSE.equals(result.get("mechanical_retry_allowed")), "the actual failed prerequisite remains visible");
            if (code.equals("build_food_unavailable")) check(Boolean.TRUE.equals(result.get("body_preparation_required")), "food shortage is not a location failure");
            if (uncertain) check(Boolean.TRUE.equals(result.get("outcome_uncertain")), "unsettled world effects cannot become a site retry");
            if (existing) check(CraftingWorkstationCoordinator.temporaryTable(h.player, at) == null, "observing an existing table after failure grants no reclamation ownership");
            check(h.blockUses() == 0 && h.itemUses() == 0, "failure handling performs no replacement build or item use");
        }
    }
    private static final class Failure implements Task {
        final String code; final boolean uncertain; int ticks;
        Failure(String code, boolean uncertain) { this.code = code; this.uncertain = uncertain; }
        public TaskState tick(LocalPlayer player) { ticks++; return TaskState.FAILED; }
        public void stop(LocalPlayer player, StopReason reason) { }
        public String name() { return "模拟工作台放置停止"; }
        public TaskResult result(TaskState state) { return TaskResult.fail(code, Map.of("failure_code", code, "outcome_uncertain", uncertain)); }
    }
    private static Object get(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
