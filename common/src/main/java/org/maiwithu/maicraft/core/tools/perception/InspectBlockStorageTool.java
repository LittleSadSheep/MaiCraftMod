package org.maiwithu.maicraft.core.tools.perception;
import org.maiwithu.maicraft.core.tools.QueryExtraOps;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw MaiCraftTool): read a block's stored items / fluid / energy without opening it. */
public final class InspectBlockStorageTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final QueryExtraOps impl = new QueryExtraOps();

    private record Args(int x, int y, int z) {}

    @Override
    public String name() {
        return "inspect_block_storage";
    }

    @Override
    public String description() {
        return "Read what a block HOLDS — items, fluid, and energy — directly from the block, WITHOUT "
                + "opening its GUI. Works on most modded machines, tanks and batteries (chests, furnaces, "
                + "Create / Mekanism / Thermal machines, fluid tanks, energy cells) because they expose "
                + "standard item/fluid/energy handlers. Give the block's integer x/y/z. Use this instead "
                + "of right-click + inspect_gui when you just need a machine's contents or fill levels. "
                + "Note: storage-network terminals (AE2/RS) show only their local buffer here, not the "
                + "whole network.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.inspectBlockStorage(a.x(), a.y(), a.z(), self));
    }
}
