package org.maiwithu.maicraft.core.task.dimension;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 回放高台旁的两列三层门：应走底部入口，第一列失败后仍尝试同门另一列。 */
public final class PortalEntranceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Set<Long> portal = new HashSet<>();
        for (int x = 8; x <= 9; x++) for (int y = 1; y <= 3; y++) portal.add(BlockPos.asLong(x, y, 3));
        var player = new Vec3(8.5, 2, 5.5);
        var nearest = portal.stream().map(BlockPos::of)
                .min(Comparator.comparingDouble(cell -> Vec3.atBottomCenterOf(cell).distanceToSqr(player))).orElseThrow();
        check(nearest.getY() == 2, "nearest portal voxel lies in the unsupported middle row");
        var entries = DimensionTravelCompanionTask.entranceCells(portal, Set.of(), player);
        check(entries.equals(List.of(new BlockPos(8, 1, 3), new BlockPos(9, 1, 3))), "both supported-height entry columns remain candidates");
        try (var w = new InteractionWorldTestHarness()) {
            var task = new DimensionTravelCompanionTask(w.player,
                    new DimensionTravelTaskRecord("portal-entrance", 1000, "minecraft:the_nether", 16, false));
            field("portal").set(task, entries.getFirst()); field("portalCells").set(task, Set.copyOf(portal));
            var reject = DimensionTravelCompanionTask.class.getDeclaredMethod("rejectCurrentPortal", String.class);
            reject.setAccessible(true);
            check(reject.invoke(task, "entry obstructed") == TaskState.RUNNING, "one blocked entrance returns to search");
            @SuppressWarnings("unchecked") var attempted = (Set<Long>) field("attempted").get(task);
            check(attempted.equals(Set.of(entries.getFirst().asLong())), "a failed entrance does not blacklist all six portal cells");
            check(DimensionTravelCompanionTask.entranceCells(portal, attempted, player).equals(List.of(entries.getLast())),
                    "the other column remains available without creating a new task");
            field("portal").set(task, entries.getLast()); field("portalCells").set(task, Set.copyOf(portal));
            reject.invoke(task, "other entry obstructed");
            check(DimensionTravelCompanionTask.entranceCells(portal, attempted, player).isEmpty(), "only exhausted entrances finish this portal");
            var dataMethod = DimensionTravelCompanionTask.class.getDeclaredMethod("resultData"); dataMethod.setAccessible(true);
            @SuppressWarnings("unchecked") var data = (Map<String, Object>) dataMethod.invoke(task);
            check(((List<?>) data.get("portal_entry_failures")).size() == 2, "receipt preserves both concrete entrance failures");
            check(Boolean.FALSE.equals(data.get("verified")), "trying entrances never claims a dimension transition");
        }
        // 水平排列的末地门不按竖门高度折叠，不会只保留一列而丢掉其他已观察入口。
        Set<Long> flat = Set.of(BlockPos.asLong(5, 1, 5), BlockPos.asLong(6, 1, 5), BlockPos.asLong(5, 1, 6));
        check(DimensionTravelCompanionTask.entranceCells(flat, Set.of(), player).size() == 3, "horizontal portal retains all entry cells");
        System.out.println("PortalEntranceTest: passed");
    }
    private static Field field(String name) throws Exception {
        var field = DimensionTravelCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
