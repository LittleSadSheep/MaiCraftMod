// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.JsonParser;
import java.util.Map;

/** 全量模式只服从当前地图，包括后来换掉或额外放入的方块；未知区块不从原设计补图。 */
public final class MachineWorldBlueprintTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var minimum = new BlockPos(3,1,3); var maximum = new BlockPos(5,1,3);
            h.set(minimum,Blocks.GOLD_BLOCK.defaultBlockState());
            h.set(minimum.east(),Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS,Direction.Axis.Z));
            var capture = MachineWorldBlueprint.page(h.level,"minecraft:overworld",BlockPos.ZERO,minimum,maximum,0,128);
            check(capture.get("capture_complete").getAsBoolean() && capture.get("air_cells").getAsInt() == 1,
                    "the complete selected range includes current empty cells");
            var rows = capture.getAsJsonArray("blocks");
            check(rows.size() == 2 && rows.get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:gold_block")
                            && rows.get(1).getAsJsonObject().getAsJsonObject("properties").get("axis").getAsString().equals("z"),
                    "as-built block identities and states come directly from the world");
            h.set(minimum,Blocks.AIR.defaultBlockState());
            var first = MachineWorldBlueprint.page(h.level,"minecraft:overworld",BlockPos.ZERO,minimum,maximum,0,1);
            check(first.getAsJsonArray("blocks").isEmpty() && first.get("next_offset").getAsInt() == 1
                            && !first.get("capture_complete").getAsBoolean(), "damage and pagination cannot be replaced with an old blueprint");
            var unloaded = MachineWorldBlueprint.page(h.level,"minecraft:overworld",BlockPos.ZERO,new BlockPos(16,1,3),new BlockPos(16,1,3),0,128);
            check(unloaded.getAsJsonArray("unknown_cells").size() == 1 && unloaded.get("air_cells").getAsInt() == 0,
                    "unloaded terrain stays unknown");
            // 对外回执必须保留这份现场数据，不能把 unknown_cells 当作内部动作格列表过滤掉。
            var publicResult = SemanticResultView.result(TaskResult.ok("observed",Map.of("machine",Map.of("as_built_blueprint",unloaded))));
            check(JsonParser.parseString(publicResult.toJson()).getAsJsonObject().getAsJsonObject("data")
                            .getAsJsonObject("machine").getAsJsonObject("as_built_blueprint").equals(unloaded),
                    "public serialization preserves the map blueprint including unknown cells");
            check(h.blockUses() == 0 && h.itemUses() == 0,"capturing a blueprint never operates on the world");
        }
        System.out.println("MachineWorldBlueprintTest: actual blocks, properties, additions, damage and unloaded cells passed");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
