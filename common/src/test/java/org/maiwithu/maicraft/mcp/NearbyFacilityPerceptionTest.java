// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 附近设施盘点：策展清单必须真实注册，段内只报类型/计数/距离，缺席语义与坐标不外泄一并守住。 */
public final class NearbyFacilityPerceptionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        curatedIdsMustBeRegistered();
        scanReportsTypesCountsAndDistancesWithoutCoordinates();
        System.out.println("NearbyFacilityPerceptionTest: passed");
    }

    private static void curatedIdsMustBeRegistered() {
        // 拼写漂移会让整段永远为空且无人察觉；每个策展 id 都必须指向真实注册方块。
        for (String id : NearbyFacilityPerception.ids()) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            check(parsed != null && BuiltInRegistries.BLOCK.containsKey(parsed),
                    "curated facility id is a registered block: " + id);
        }
    }

    private static void scanReportsTypesCountsAndDistancesWithoutCoordinates() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 10, 4.5));
            h.set(new BlockPos(2, 10, 2), Blocks.STONECUTTER.defaultBlockState());
            h.set(new BlockPos(7, 10, 7), Blocks.CHEST.defaultBlockState());
            h.set(new BlockPos(8, 10, 7), Blocks.CHEST.defaultBlockState());
            // 清单外方块（木板）不是设施；两个箱子聚合成一行计数，不逐格罗列。
            h.set(new BlockPos(4, 9, 4), Blocks.OAK_PLANKS.defaultBlockState());
            var section = NearbyFacilityPerception.observe(h.player);
            check(section.get("radius").getAsInt() == 16, "the section declares its scan radius");
            var facilities = section.getAsJsonArray("facilities");
            check(facilities.size() == 2, "only curated facility types are reported");
            check("minecraft:stonecutter".equals(facilities.get(0).getAsJsonObject().get("block_id").getAsString()),
                    "facilities sort by nearest distance");
            var chest = facilities.get(1).getAsJsonObject();
            check("minecraft:chest".equals(chest.get("block_id").getAsString()) && chest.get("count").getAsInt() == 2,
                    "same-type matches aggregate into one row with a count");
            check(chest.get("nearest_distance").getAsDouble() > 0, "nearest distance is measured from the standing place");
            check(section.get("scope").getAsString().contains("curated"), "absence carries an honest scope note");
            check(noCoordinates(section), "no coordinate field leaks into the facilities section");
        }
    }

    private static boolean noCoordinates(JsonElement value) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (key.equals("x") || key.equals("y") || key.equals("z") || key.equals("position")) return false;
                if (!noCoordinates(entry.getValue())) return false;
            }
            return true;
        }
        if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) if (!noCoordinates(item)) return false;
            return true;
        }
        return true;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
