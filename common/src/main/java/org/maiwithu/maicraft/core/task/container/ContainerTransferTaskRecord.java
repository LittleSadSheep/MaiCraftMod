package org.maiwithu.maicraft.core.task.container;

import java.util.List;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * Live menu-slot transfers. {@code to=-1} means whole-stack QUICK_MOVE; count 0 means all,
 * and swaps whole stacks when an explicit destination contains another item kind.
 */
public final class ContainerTransferTaskRecord extends TaskRecord {
    static { TaskFactory.register(ContainerTransferTaskRecord.class, ContainerTransferCompanionTask::new); }
    public record Move(int from, int to, int count) {
        public Move {
            if (from < 0 || to < -1 || count < 0) throw new IllegalArgumentException("invalid transfer move");
        }
    }
    public final int expectedContainerId;
    public final List<Move> moves;
    public ContainerTransferTaskRecord(String callId, long deadline, int expectedContainerId,
                                       List<Move> moves) {
        super("container_transfer", callId, deadline);
        this.expectedContainerId = expectedContainerId;
        this.moves = List.copyOf(moves);
        if (this.moves.isEmpty()) throw new IllegalArgumentException("moves must not be empty");
    }
    @Override public String describe() { return "container_transfer " + moves.size() + " move(s)"; }
}
