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
 * 登记旧的 locate_structure 内部工具，转成旧结构定位任务；现用语义搜索走 structure_search。
 * 目前下游始终报告无法取得权威结构位置；多加载地形再重试，也不会让这条入口返回找到。
 */
public final class LocateStructureTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final LocateOps impl = new LocateOps();

    private record Args(String structure) {}

    @Override
    public String name() {
        return "locate_structure";
    }

    @Override
    public String description() {
        return "Check whether currently loaded client facts can verify a structure of the requested id or "
                + "#tag in the current dimension. The client does not know the world's seed or authoritative "
                + "structure starts, so this never performs a hidden global locate and never invents distant "
                + "coordinates. If loaded facts cannot prove it, the result is UNKNOWN with a recovery: "
                + "travel to load more terrain, then use visible block/entity clues and scan again. Examples: "
                + "minecraft:fortress, minecraft:bastion_remnant, minecraft:ancient_city, "
                + "minecraft:end_city, minecraft:mansion, #minecraft:village.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("structure", "Structure id (e.g. minecraft:fortress) or #tag (e.g. #minecraft:village).")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.locateStructure(a.structure(), ctx(toolCallId, companion)), reply);
    }
}
