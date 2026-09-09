package org.maiwithu.maicraft.core.task.menu;

import java.util.List;
import net.minecraft.world.inventory.ClickType;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 一组有顺序的菜单点击要求；记录复制列表并拒绝空列表。
 * 每步等菜单接口的结果，实际确认是否包含服务器状态变化取决于公共菜单接口，并非本记录保证。
 */
public final class MenuSequenceTaskRecord extends TaskRecord {
    static { TaskFactory.register(MenuSequenceTaskRecord.class, MenuSequenceCompanionTask::new); }
    // 每一步保存菜单槽号、鼠标键、点击方式和失败后放回鼠标物品的槽号；默认回到本次点击格。
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
