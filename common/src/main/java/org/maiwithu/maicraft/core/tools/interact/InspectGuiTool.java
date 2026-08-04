package org.maiwithu.maicraft.core.tools.interact;
import org.maiwithu.maicraft.core.tools.GuiOps;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw MaiCraftTool): look at the currently open GUI / inventory menu. */
public final class InspectGuiTool implements MaiCraftTool {

    private final GuiOps impl = new GuiOps();

    @Override
    public String name() {
        return "inspect_gui";
    }

    @Override
    public String description() {
        return "Look at the GUI you currently have open. After interact_at right-clicks a chest / "
                + "furnace / machine it shows that container; with NO container open it shows YOUR own "
                + "inventory menu (which includes a 2x2 crafting grid), so you can craft small recipes "
                + "without a table. Lists every slot — index, side, item + count, [output] mark — plus "
                + "the cursor and any machine progress. If a crafting grid is open it draws the grid as "
                + "a 2D map of slot numbers. Use it to understand a modded machine, choose transfer "
                + "sources/destinations, and verify the result. Ordinary crafting belongs to craft, which "
                + "places the recipe itself; do not manually click every ingredient. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        reply.accept(impl.inspectGui(self));
    }
}
