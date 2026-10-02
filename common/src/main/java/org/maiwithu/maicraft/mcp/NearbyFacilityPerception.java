// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 附近可立即使用的方块设施盘点：只报类型、计数与最近距离，不给坐标——
 * 目标解析由能力层自定位（interact/manage_container 的 nearest），感知层不替能力选目标。
 * 清单是策展的：列出的类型才报告；缺席声明必须随段携带，不构成"清单外方块不存在"的证据。
 */
final class NearbyFacilityPerception {
    private static final int RADIUS = 16;

    /** 策展清单：明确 id；床/铁砧/锅按 tag 覆盖颜色与变体，避免逐个枚举 16 色。 */
    private static final Set<String> BLOCK_IDS = Set.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:stonecutter", "minecraft:smithing_table", "minecraft:grindstone", "minecraft:enchanting_table",
            "minecraft:loom", "minecraft:cartography_table", "minecraft:fletching_table", "minecraft:brewing_stand",
            "minecraft:composter", "minecraft:lectern", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box", "minecraft:ender_chest",
            "minecraft:hopper", "minecraft:beacon", "minecraft:bell", "minecraft:respawn_anchor", "minecraft:jukebox");

    private NearbyFacilityPerception() {}

    static JsonObject observe(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        Map<String, Match> matches = new HashMap<>();
        // 按需请求才执行的一次有界扫描；只读已加载区块，未加载部分按 scope 声明如实保留为未知。
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -RADIUS, -RADIUS), center.offset(RADIUS, RADIUS, RADIUS))) {
            if (!player.level().isLoaded(pos)) continue;
            BlockState state = player.level().getBlockState(pos);
            if (state.isAir()) continue;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (!BLOCK_IDS.contains(id.toString()) && !state.is(BlockTags.BEDS) && !state.is(BlockTags.ANVIL)
                    && !state.is(BlockTags.CAULDRONS))
                continue;
            Match match = matches.computeIfAbsent(id.toString(), ignored -> new Match());
            match.count++;
            match.nearest = Math.min(match.nearest,
                    Math.sqrt(player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        }
        List<Map.Entry<String, Match>> ordered = new ArrayList<>(matches.entrySet());
        ordered.sort(Comparator.comparingDouble(entry -> entry.getValue().nearest));
        JsonArray facilities = new JsonArray();
        for (var entry : ordered) {
            JsonObject row = new JsonObject();
            row.addProperty("block_id", entry.getKey());
            row.addProperty("count", entry.getValue().count);
            row.addProperty("nearest_distance", Math.round(entry.getValue().nearest * 10.0) / 10.0);
            facilities.add(row);
        }
        JsonObject result = new JsonObject();
        result.addProperty("radius", RADIUS);
        result.add("facilities", facilities);
        result.addProperty("scope", "loaded blocks within radius " + RADIUS + " of the standing position, curated facility list only; "
                + "absence proves neither the absence of unlisted usable blocks nor anything beyond this radius; "
                + "targets resolve through abilities (nearest/landmark), never through coordinates");
        return result;
    }

    /** 策展清单只读暴露给测试：拼写漂移会让整段永远为空，注册表核对必须在回归里做。 */
    static Set<String> ids() { return BLOCK_IDS; }

    private static final class Match {
        int count;
        double nearest = Double.MAX_VALUE;
    }
}
