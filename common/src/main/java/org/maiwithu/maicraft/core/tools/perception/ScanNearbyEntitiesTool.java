package org.maiwithu.maicraft.core.tools.perception;
import org.maiwithu.maicraft.core.tools.QueryExtraOps;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw MaiCraftTool): list nearby entities, sorted by distance. */
public final class ScanNearbyEntitiesTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final QueryExtraOps impl = new QueryExtraOps();

    private record Args(double radius, String type_filter) {}

    @Override
    public String name() {
        return "scan_nearby_entities";
    }

    /** 常驻:找实体;与 scan_blocks 靠描述区分,摘要不足以选对。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return "List entities within a radius around you, sorted by distance. Use type_filter to "
                + "narrow: 'hostile' for monsters, 'passive' for animals/items, 'player' for players, "
                + "'all' for everything. Returns at most 20 entities; truncated:true means more exist. "
                + "Each entry has id, type, position, distance, hp, and category. Pass "
                + "the returned runtime ids to attack; it cannot attack anything outside that set.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .number("radius", "Search radius in blocks. Range [1, 64].", 1, 64)
                .enumStr("type_filter", "One of: hostile, passive, player, all.",
                        "hostile", "passive", "player", "all")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.scanNearbyEntities(a.radius(), a.type_filter(), self));
    }
}
