package org.maiwithu.maicraft.core.tools.interact;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.runSync;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Close the active block menu through a confirmed client transaction. */
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
        if (self.containerMenu == self.inventoryMenu) {
            reply.accept(TaskResult.ok(
                    "no block GUI is open; the player inventory menu is already the active baseline").toJson());
            return;
        }
        runSync(self, new CloseMenuTaskRecord(
                toolCallId, ctx(toolCallId, self).deadline(TIMEOUT_TICKS)), reply);
    }
}
