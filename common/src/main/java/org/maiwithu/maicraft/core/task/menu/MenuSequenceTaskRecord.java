package org.maiwithu.maicraft.core.task.menu;

import java.util.List;
import net.minecraft.world.inventory.ClickType;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Ordered menu clicks; each click waits for its own state-id receipt before the next is sent. */
public final class MenuSequenceTaskRecord extends TaskRecord {
    static { TaskFactory.register(MenuSequenceTaskRecord.class, MenuSequenceCompanionTask::new); }
    public record Click(int slot, int button, ClickType type, int rollbackSlot) {
        public Click(int slot, int button, ClickType type) { this(slot, button, type, slot); }
    }
    public final int expectedContainerId;
    public final List<Click> clicks;

    public MenuSequenceTaskRecord(String callId, long deadline, int expectedContainerId,
                                  List<Click> clicks) {
        super("menu_sequence", callId, deadline);
        this.expectedContainerId = expectedContainerId;
        this.clicks = List.copyOf(clicks);
        if (this.clicks.isEmpty()) throw new IllegalArgumentException("clicks must not be empty");
    }

    @Override public String describe() { return "menu_sequence " + clicks.size() + " click(s)"; }
}
