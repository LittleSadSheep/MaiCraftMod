package org.maiwithu.maicraft.core.tools.perception;
import org.maiwithu.maicraft.core.tools.PerceptionOps;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** 查询工具（原始 MaiCraftTool）：读取当前世界状态。 */
public final class GetWorldInfoTool implements MaiCraftTool {

    private final PerceptionOps impl = new PerceptionOps();

    @Override
    public String name() {
        return "get_world_info";
    }

    @Override
    public String description() {
        return "Read the current world state: dimension, game-time tick counter, whether it's "
                + "bright or dark outside (combat / spawn planning), and weather (clear / rain / "
                + "thunder, affects sailing and combat). No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        reply.accept(impl.getWorldInfo(self));
    }
}
