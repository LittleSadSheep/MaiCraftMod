package org.maiwithu.maicraft.core.tools.perception;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.tools.ScaffoldOps;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登记工具(当场返回):管她愿意拿来垫路的方块清单。不占身体。
 *
 * <p>每次调用——包括只读的那次——都把<b>当前完整清单</b>和<b>背包里还没进清单的方块</b>
 * 一起返回,所以读一次就够做决定,不用再去查背包。
 */
public final class ScaffoldMaterialsTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final ScaffoldOps impl = new ScaffoldOps();

    private record Args(String action, List<String> block_ids) {}

    @Override
    public String name() {
        return "scaffold_materials";
    }

    @Override
    public String description() {
        return "Manage which blocks you are willing to spend as throwaway scaffolding — the blocks "
                + "pathfinding sacrifices to pillar up, bridge a gap or step over a ledge. This is a "
                + "standing choice that persists across sessions and is used by every move you make, "
                + "including reflexes like fleeing. Call it with no action to just look. Anything you "
                + "list WILL be consumed and never comes back, so list what you consider junk here and "
                + "now: cobblestone is junk in a mineshaft and precious in the End. The reply always "
                + "carries the full current list plus what you are carrying that is not on it, so one "
                + "call is enough to decide. Emptying the list is a real choice — do it when what you "
                + "carry is earmarked for something (the dirt is for a build), and accept that she "
                + "then cannot pillar or bridge at all. The local player is notified when it changes.";
    }

    @Override
    // add/delete 只增删指定项，set 替换整张清单，clear 清空；不填 action 只读取。清单决定哪些方块能被寻路消耗。
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "add appends, delete removes, set replaces the whole list, "
                        + "clear empties it so nothing at all may be spent. Omit to just read.",
                        "add", "delete", "set", "clear")
                .optionalStringArray("block_ids", "Namespaced block ids, e.g. "
                        + "['minecraft:cobbled_deepslate']. Required for add / delete / set; "
                        + "ignored by clear.")
                .build();
    }

    @Override
    // 这里只解析参数并转交 ScaffoldOps。实际修改、保存、通知玩家和整理返回清单都在那一层完成。
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.apply(
                a == null ? null : a.action(),
                a == null ? null : a.block_ids(),
                self));
    }
}
