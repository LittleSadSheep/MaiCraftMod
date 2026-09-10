package org.maiwithu.maicraft.core.tools.locate;
import org.maiwithu.maicraft.core.tools.LocateOps;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 向外提供 locate_biome 工具，把 JSON 交给 LocateOps 建立客户端抽样任务。
 * 工具描述目前写“最近”，但下游会在第一个匹配采样点结束。
 */
public final class LocateBiomeTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final LocateOps impl = new LocateOps();

    private record Args(String biome) {}

    @Override
    public String name() {
        return "locate_biome";
    }

    @Override
    public String description() {
        return "Find the nearest matching biome sample in terrain already loaded by this client, in the "
                + "current dimension. `biome` may be an id such as minecraft:warped_forest, "
                + "minecraft:soul_sand_valley, minecraft:desert, minecraft:plains or minecraft:dark_forest, "
                + "or a #tag such as #minecraft:is_forest. This does not query the seed or unseen chunks. "
                + "If no loaded sample matches, the result says the target is still unknown: travel to load "
                + "more terrain and call again. A found sample is a navigation hint; confirm conditions on arrival.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("biome", "Biome id (e.g. minecraft:warped_forest) or #tag (e.g. #minecraft:is_forest).")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.locateBiome(a.biome(), ctx(toolCallId, companion)), reply);
    }
}
