package org.maiwithu.maicraft.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;

/** Read-only validation and planning for synchronized container transfers. */
public final class ContainerOps {

    private static final long MIN_TIMEOUT_TICKS = 30L * 20L;
    private static final long MAX_INITIAL_LEASE_TICKS = 10L * 60L * 20L;

    /** One semantic move. A null destination means menu-routed whole-stack transfer. */
    public record Move(int from, Integer to, Integer count) {}

    public record Plan(ContainerTransferTaskRecord task, TaskResult immediate) {
        public boolean executable() {
            return task != null;
        }
    }

    /**
     * Freeze only the menu identity and semantic requests. Slot contents remain live facts owned by
     * the cross-tick task, so later moves can depend on changes made by earlier moves.
     */
    public Plan plan(List<Move> moves, LocalPlayer self, ToolContext context) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            return immediateFailure("no container or machine GUI is open — interact with one first",
                    Map.of("recovery", "open_container_or_machine"));
        }
        if (moves == null || moves.isEmpty()) {
            return immediateFailure("moves must contain at least one transfer", Map.of());
        }

        int max = menu.slots.size() - 1;
        List<ContainerTransferTaskRecord.Move> planned = new ArrayList<>();
        long estimatedClicks = 0;
        for (int index = 0; index < moves.size(); index++) {
            Move move = moves.get(index);
            if (move == null) {
                return invalidMove(index, "move is null", max);
            }
            if (move.from() < 0 || move.from() > max) {
                return invalidMove(index, "source slot is outside 0.." + max, max);
            }
            int destination = move.to() == null ? -1 : move.to();
            if (destination < -1 || destination > max) {
                return invalidMove(index, "destination slot is outside 0.." + max, max);
            }
            if (destination == move.from()) {
                continue;
            }

            // QUICK_MOVE is defined by the live menu and always routes the whole stack. An exact
            // count therefore applies only to an explicit destination.
            int count = destination < 0 || move.count() == null || move.count() <= 0
                    ? 0 : move.count();
            planned.add(new ContainerTransferTaskRecord.Move(move.from(), destination, count));
            estimatedClicks += destination < 0 ? 1L : count > 0 ? count + 2L : 3L;
        }
        if (planned.isEmpty()) {
            return new Plan(null, TaskResult.ok("all requested transfers were source-to-self no-ops",
                    Map.of("completed_moves", 0)));
        }

        long timeout = Math.clamp(20L + estimatedClicks * 25L,
                MIN_TIMEOUT_TICKS, MAX_INITIAL_LEASE_TICKS);
        return new Plan(new ContainerTransferTaskRecord(
                context.toolCallId(), context.deadline(timeout), menu.containerId, planned), null);
    }

    private static Plan invalidMove(int zeroBasedIndex, String problem, int maxSlot) {
        return immediateFailure("transfer move " + (zeroBasedIndex + 1) + " is invalid: " + problem,
                Map.of("move_index", zeroBasedIndex,
                        "available_slot_min", 0,
                        "available_slot_max", maxSlot,
                        "recovery", "inspect_gui"));
    }

    private static Plan immediateFailure(String message, Map<String, Object> data) {
        return new Plan(null, TaskResult.fail(message, data));
    }
}
