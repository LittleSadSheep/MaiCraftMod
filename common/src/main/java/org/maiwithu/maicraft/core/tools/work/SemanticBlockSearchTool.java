// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;

/** 语义方块发现；坐标留在 Mod 内部，岩浆额外报告可见连通池与浇筑整形预算。 */
public final class SemanticBlockSearchTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();

    private record Args(List<String> block_ids, Integer count, Integer max_distance, String purpose) {}

    @Override
    public String name() {
        return SemanticBlockSearchTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        // 公开描述说明只有直接可见目标才计入找到的数量，避免模型把加载范围当作透视。
        return "Find named blocks by scanning the currently loaded client chunks around the "
                + "standing place with direct line of sight from the player's eyes. Reports verified counts, matching block ids and a "
                + "nearest-distance statistic. When lava is requested, finishes the bounded scan even for count=1 and also reports "
                + "connected visible surface pools, straight-bank length, platform fill costs and remaining-source lower bounds. "
                + "Use purpose=portal_casting to require count suitable pools, each with a casting start row and at least 15 sources left after filling. "
                + "A block match is not proof of a usable casting pool; native access and fluid outcomes remain unverified. Concrete positions stay inside the Mod and "
                + "a later semantic ability resolves the actual block itself. No frontier "
                + "walking, chunk forcing or coordinate output; unloaded terrain stays unknown.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalStringArray("block_ids",
                        "Acceptable namespaced block ids, required for blocks; may be omitted for portal_casting.")
                .optionalInteger("count",
                        "Required matching block positions, or matching pools for purpose=portal_casting; partial counts fail.",
                        1, SemanticBlockSearchTaskRecord.MAX_COUNT)
                // 用途只改变只读查找的成功条件，不授权移动、整形池岸或倒桶。
                .optionalEnum("purpose", "blocks by default; portal_casting requires observed casting geometry and source reserve.",
                        "blocks", "portal_casting")
                .optionalInteger("max_distance",
                        "Bounded loaded-world scan radius from the standing place.",
                        SemanticBlockSearchTaskRecord.MIN_DISTANCE, SemanticBlockSearchTaskRecord.MAX_DISTANCE)
                .build();
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        var record = SemanticBlockSearchApi.newRecord(
                ctx(toolCallId, player),
                parsed == null ? null : parsed.block_ids(),
                parsed == null ? null : parsed.count(),
                parsed == null ? null : parsed.max_distance(),
                parsed == null ? null : parsed.purpose());
        setTask(player, record, args, reply);
    }
}
