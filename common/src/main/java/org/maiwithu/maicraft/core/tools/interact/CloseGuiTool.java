package org.maiwithu.maicraft.core.tools.interact;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.runSync;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuCloseTaskRecord;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** 通过已确认的客户端事务关闭当前方块菜单。 */
public final class CloseGuiTool implements MaiCraftTool {

    private static final long TIMEOUT_TICKS = 5L * 20L;

    @Override
    public String name() {
        return "close_gui";
    }

    @Override
    public String description() {
        return "Close the container GUI you currently have open — do this when you've finished "
                + "moving items. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        // 默认物品栏菜单常驻不代表页面已经退出；暂停、背包和模组页面都交给同一原生关闭任务。
        runSync(self, new MachineMenuCloseTaskRecord(
                toolCallId, ctx(toolCallId, self).deadline(TIMEOUT_TICKS)), reply);
    }
}
