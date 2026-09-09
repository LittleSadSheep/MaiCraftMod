package org.maiwithu.maicraft.core.task.container;

import java.util.List;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存一组有顺序的菜单搬运要求，默认全部完成后关闭界面。
 * closeAfter=false 留给需要继续检查或操作同一菜单的父任务；创建任务单不会发出点击。
 */
public final class ContainerTransferTaskRecord extends TaskRecord {
    static { TaskFactory.register(ContainerTransferTaskRecord.class, ContainerTransferCompanionTask::new); }
    public enum DestinationMode {
        /** The destination stack itself must show the exact deposited amount. */
        EXACT,
        /** A synchronized machine may consume or transform the deposit immediately. */
        MAY_MUTATE_AFTER_DEPOSIT
    }
    // from 和 to 都是菜单槽号；to=-1 表示让原版快速移动，count=0 表示整堆。
    // EXACT 要求看到目标格准确增加；MAY_MUTATE_AFTER_DEPOSIT 用于放进去就可能被机器消耗的物品。
    public record Move(int from, int to, int count, DestinationMode destinationMode) {
        public Move(int from, int to, int count) {
            this(from, to, count, DestinationMode.EXACT);
        }
        public Move {
            if (from < 0 || to < -1 || count < 0 || destinationMode == null) {
                throw new IllegalArgumentException("invalid transfer move");
            }
        }
    }
    // 绑定开始时的菜单编号，执行器还会记住菜单对象，避免在后来换出的界面里沿用旧槽号。
    public final int expectedContainerId;
    public final List<Move> moves;
    public final boolean closeAfter;
    public ContainerTransferTaskRecord(String callId, long deadline, int expectedContainerId,
                                       List<Move> moves) {
        this(callId, deadline, expectedContainerId, moves, true);
    }
    /** Composed tasks retain the visible menu until their complete transaction finishes. */
    public ContainerTransferTaskRecord(String callId, long deadline, int expectedContainerId,
                                       List<Move> moves, boolean closeAfter) {
        super("container_transfer", callId, deadline);
        this.expectedContainerId = expectedContainerId;
        this.moves = List.copyOf(moves);
        this.closeAfter = closeAfter;
        if (this.moves.isEmpty()) throw new IllegalArgumentException("moves must not be empty");
    }
    @Override public String describe() { return "container_transfer " + moves.size() + " move(s)"; }
}
