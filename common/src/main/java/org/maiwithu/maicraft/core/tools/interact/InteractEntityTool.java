package org.maiwithu.maicraft.core.tools.interact;
import org.maiwithu.maicraft.core.tools.BlockActionOps;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 接收内部 interact_entity 请求，交给 BlockActionOps 建立对实体的点击任务；靠近、对准和实际操作由执行任务完成。
 */
public final class InteractEntityTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final BlockActionOps impl = new BlockActionOps();

    private record Args(String button, int entity_id, Integer hold_ticks, String item_id) {}

    @Override
    public String name() {
        return "interact_entity";
    }

    @Override
    public String description() {
        return "Press a mouse button on an ENTITY — the native interaction for moving targets. Auto-paths "
                + "and FOLLOWS the entity (id from scan_nearby_entities), then acts once the crosshair "
                + "reaches it (a wall in the way makes it re-position, not hit through). right on a "
                + "boat/rideable = board it (runtime_state then shows <riding>; goto pilots or steps "
                + "off — never click your own vehicle again).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("button", "left = attack/hit, right = use/interact.", "left", "right")
                .integer("entity_id", "Target entity id (from scan_nearby_entities).")
                .nullableInteger("hold_ticks", "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout (e.g. attack until dead).")
                .nullableString("item_id", "Optional namespaced item to equip-and-use, e.g. minecraft:wheat. Null = use what's in hand.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.interactEntity(a.button(), a.entity_id(), a.hold_ticks(), a.item_id(),
                ctx(toolCallId, companion)), reply);
    }
}
