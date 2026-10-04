// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;

/** hunt 搜索距离上限经参数生效：默认与历史一致，显式值进入任务单，越界值在公开入口被拒绝。 */
public final class HuntSearchDistanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var record = SemanticAcquireApi.newRecord(
                    new ToolContext("hunt-default", 0), huntArgs(null), world.player);
            check(record.huntSearchDistance == GenericEntitySearchTaskRecord.DEFAULT_DISTANCE,
                    "未指定 max_distance 时保持历史默认搜索距离");

            var bounded = SemanticAcquireApi.newRecord(
                    new ToolContext("hunt-bounded", 0), huntArgs(64), world.player);
            check(bounded.huntSearchDistance == 64, "显式 max_distance 进入任务单");

            var direct = new SemanticAcquireTaskRecord(
                    "hunt-direct", 0, List.of(id("porkchop")), 1,
                    List.of(SemanticAcquireTaskRecord.Source.HUNT), true,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16)
                    .withHuntSearchDistance(99_999);
            check(direct.huntSearchDistance == GenericEntitySearchTaskRecord.MAX_DISTANCE,
                    "内部入口的越界值夹取到上限");

            for (int invalid : new int[]{0, -8, 99_999}) {
                try {
                    SemanticAcquireApi.validateArguments(huntArgs(invalid));
                    throw new AssertionError("越界 max_distance=" + invalid + " 未被拒绝");
                } catch (IllegalArgumentException expected) { }
            }
        }
        System.out.println("HuntSearchDistanceTest: passed");
    }

    private static JsonObject huntArgs(Integer maxDistance) {
        var args = JsonParser.parseString("""
                {"item_id":"minecraft:porkchop","allow_harm":true,
                 "allowed_sources":["hunt"],
                 "source_hint":{"entity_type_ids":["minecraft:pig"],
                                "expected_item_ids":["minecraft:porkchop"]}}
                """).getAsJsonObject();
        if (maxDistance != null) args.addProperty("max_distance", maxDistance);
        return args;
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.withDefaultNamespace(value);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
