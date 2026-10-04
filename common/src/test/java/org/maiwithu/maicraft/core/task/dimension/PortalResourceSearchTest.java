// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 资源发现与走近分开验收：流水不能替代静源；附近空手后才跑图，找到目标即结束跑图。 */
public final class PortalResourceSearchTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 2, 5.5));
            h.set(new BlockPos(6, 1, 5), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
            h.set(new BlockPos(8, 1, 5), Blocks.WATER.defaultBlockState());
            var record = new SemanticBlockSearchTaskRecord("water-source", 1000, List.of(Blocks.WATER), 1, 16).sourceFluidsOnly();
            var task = new SemanticBlockSearchCompanionTask(h.player, record); task.start(h.player);
            var terminal = TaskState.RUNNING;
            for (int tick = 0; tick < 128 && terminal == TaskState.RUNNING; tick++) { h.nextTick(); terminal = task.tick(h.player); }
            check(terminal == TaskState.SUCCESS && Map.of("x",8,"y",1,"z",5).equals(task.result(terminal).data().get("nearest_match_position")),
                    "a closer flowing block cannot replace a collectible water source");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 2, 5.5));
            int[] scans = {0}; boolean[] stopped = {false};
            var record = new PortalResourceSearchTaskRecord("distant-water", 3000,
                    PortalResourceSearchTaskRecord.Resource.WATER, 16, 128, false);
            var task = new PortalResourceSearchTask(h.player, record, (player, child) -> new Task() {
                final boolean lookup = child instanceof SemanticBlockSearchTaskRecord;
                final boolean found = lookup && scans[0]++ > 0;
                public String name() { return "resource_search_fixture"; }
                public void stop(LocalPlayer player, StopReason why) { if (!lookup) stopped[0] = true; }
                public TaskState tick(LocalPlayer player) {
                    if (lookup) return found ? TaskState.SUCCESS : TaskState.FAILED;
                    check(child instanceof SemanticExploreTaskRecord && !((SemanticExploreTaskRecord) child).mayAlterTerrain,
                            "resource exploration preserves terrain permission");
                    // 夹具只重放一次走到新视点；实际走路和世界装载另由实机测试确认。
                    try { h.position(new Vec3(14.5, 2, 5.5)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    return TaskState.RUNNING;
                }
                public TaskResult result(TaskState terminal) {
                    return found ? TaskResult.ok("visible source observed", Map.of("nearest_match_position", Map.of("x",14,"y",1,"z",5)))
                            : TaskResult.fail("no local source", Map.of("fixture",true));
                }
            });
            task.start(h.player); var terminal = TaskState.RUNNING;
            for (int tick = 0; tick < 16 && terminal == TaskState.RUNNING; tick++) { h.nextTick(); terminal = task.tick(h.player); }
            check(terminal == TaskState.SUCCESS && scans[0] == 2 && stopped[0], "found water ends exploration without another model task");
            check(record.observedPosition.equals(new BlockPos(14,1,5)), "the parent receives the observed source location");
            check(((List<?>) task.result(terminal).data().get("lookups")).size() == 2, "failed local and successful new-view evidence both survive");
        }
        System.out.println("PortalResourceSearchTest: source filtering and bounded exploration handoff passed");
    }
}
