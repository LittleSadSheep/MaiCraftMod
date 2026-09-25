// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 真实已加载区块中的缺块、错块、朝向与占位分别返回；分页和未加载区域不伪造完整匹配。 */
public final class MachineBlueprintDiffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var blueprint = JsonParser.parseString("""
                {"schema_version":1,"blocks":[
                  {"offset":[1,1,8],"block_id":"minecraft:stone"},
                  {"offset":[2,1,8],"block_id":"minecraft:stone"},
                  {"offset":[3,1,8],"block_id":"minecraft:stone"},
                  {"offset":[4,1,8],"block_id":"minecraft:oak_log","properties":{"axis":"x"}},
                  {"offset":[5,1,8],"block_id":"minecraft:air"},
                  {"offset":[16,1,8],"block_id":"minecraft:stone"}]}
                """).getAsJsonObject();
        var plan = MachineConstructionPlan.compile(BlockPos.ZERO,
                MachineBlueprintDocument.compile(blueprint,MachineConstructionPlan.registry()), false);
        var diff = new MachineBlueprintDiff(plan);
        try (var h = new InteractionWorldTestHarness()) {
            h.set(new BlockPos(1,1,8),Blocks.STONE.defaultBlockState());
            h.set(new BlockPos(3,1,8),Blocks.GOLD_BLOCK.defaultBlockState());
            h.set(new BlockPos(4,1,8),Blocks.OAK_LOG.defaultBlockState());
            h.set(new BlockPos(5,1,8),Blocks.DIRT.defaultBlockState());
            var result = diff.page(h.level,"minecraft:overworld",0,128);
            for (String key : new String[]{"matched","missing","wrong_block","wrong_state","unexpected","unknown"})
                check(result.get(key).getAsInt() == 1,"actual difference category: " + key);
            check(!result.get("comparison_complete").getAsBoolean() && result.get("structure_matches_blueprint").isJsonNull(),
                    "unloaded cells keep whole-machine agreement unknown");
            var first = diff.page(h.level,"minecraft:overworld",0,2);
            var second = diff.page(h.level,"minecraft:overworld",first.get("next_offset").getAsInt(),2);
            check(first.get("examined").getAsInt() == 2 && second.get("offset").getAsInt() == 2
                            && first.get("structure_matches_blueprint").isJsonNull(), "pages preserve target order without claiming a complete comparison");
            // 地图后来被修改时重读实际方块，不能拿上次的匹配结果当作当前仍然建好的证明。
            h.set(new BlockPos(1,1,8),Blocks.AIR.defaultBlockState());
            check(diff.page(h.level,"minecraft:overworld",0,1).get("missing").getAsInt() == 1,"inspection observes later damage");
            check(diff.page(h.level,"minecraft:the_nether",0,128).get("unknown").getAsInt() == 6,"another dimension cannot supply false observations");
            // 完工后的默认比较只扫描一轮，读完不会转成后台持续监控；未加载仍然作为未知保留。
            var comparison = new MachineBlueprintComparison(plan,"minecraft:overworld");
            check(!comparison.advance(h.level,2) && comparison.report().get("examined").getAsInt() == 2,"completion diff yields between chunks of targets");
            h.nextTick(); check(comparison.advance(h.level,128),"one complete comparison finishes");
            int reads = h.level.blockReads; h.nextTick(); comparison.advance(h.level,128);
            check(h.level.blockReads == reads && !comparison.report().get("comparison_complete").getAsBoolean(),
                    "the completed scan stays frozen and does not turn unloaded cells into missing blocks");
            check(h.blockUses() == 0 && h.itemUses() == 0 && h.inventory.isEmpty(),"diff never places, removes, supplies or uses blocks");
        }
        System.out.println("MachineBlueprintDiffTest: current-world categories, paging, damage and unloaded state passed");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
