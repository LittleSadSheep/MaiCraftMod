package org.maiwithu.maicraft.core.tools.perception;
import org.maiwithu.maicraft.core.tools.PerceptionOps;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Compatibility query for installations that still expose the historical tool name. */
public final class GetOwnerStatusTool implements MaiCraftTool {

    private final PerceptionOps impl = new PerceptionOps();

    @Override
    public String name() {
        return "get_owner_status";
    }

    @Override
    public String description() {
        return "Compatibility alias for reading the current local player's status: name, HP, "
                + "hunger, position, dimension, and held items. First-person automation has no "
                + "separate implicit owner, so this tool never invents one. Use an explicit entity "
                + "id from scan_nearby_entities for follow, protect, or rendezvous decisions. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        reply.accept(impl.getOwnerStatus(self));
    }
}
