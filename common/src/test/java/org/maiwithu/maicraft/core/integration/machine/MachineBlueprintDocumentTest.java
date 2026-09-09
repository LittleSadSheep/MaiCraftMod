// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/**
 * 检查当前显式蓝图入口保留负偏移、空气格和独立计划副本，拒绝无法安装的状态与未声明的生成格；大蓝图不再沿用旧的八格半径和五百一十二格限制。
 */
public final class MachineBlueprintDocumentTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registry = MachineConstructionPlan.registry();
        JsonObject input = document("""
                {"offset":[-12,-1,0],"block_id":"minecraft:barrel","properties":{"facing":"north"}},
                {"offset":[0,0,0],"block_id":"minecraft:air"}
                """);
        input.add("evidence", JsonParser.parseString("{\"observed_nbt\":{\"Speed\":32}}"));
        var layout = MachineBlueprintDocument.compile(input, registry);
        check(layout.buildable(), "registered explicit structure compiles outside old 8-block radius");
        check(!layout.blueprint().has("evidence"), "raw tutorial evidence does not become task configuration");
        BlockPos anchor = new BlockPos(100, 64, 100);
        var plan = MachineConstructionPlan.compile(anchor, layout, true, true);
        check(plan.blocks().size() == 2, "no inferred machine parts or omitted-space clearing");
        check(plan.blocks().getFirst().pos().equals(anchor.offset(-12, -1, 0)), "exact anchor and negative offsets preserved");
        check(plan.blocks().get(1).desiredState().isAir(), "explicit air retains clearance target");
        check(plan.report().get("explicit_blueprint").getAsBoolean(), "explicit construction does not promise operation");
        input.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block_id", "minecraft:gold_block");
        check(plan.blocks().getFirst().label().equals("minecraft:barrel"), "compiled plan detached from caller edits");

        rejects(document("{\"offset\":[0.5,0,0],\"block_id\":\"minecraft:stone\"}"));
        rejects(document("{\"offset\":[0,0,0],\"block_id\":\"stone\"}"));
        rejects(document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\",\"properties\":{\"axis\":1}}"));
        rejects(document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\",\"execute\":\"command\"}"));
        rejects(document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:air\"},{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}"));
        JsonObject future = document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}");
        future.addProperty("schema_version", 2); rejects(future);
        JsonObject invalid = document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:barrel\",\"properties\":{\"facing\":\"sideways\"}}");
        check(!MachineBlueprintDocument.compile(invalid, registry).buildable(), "invalid state rejected in review");
        JsonObject nbt = document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:barrel\",\"nbt\":{\"Items\":[]}}");
        check(!MachineBlueprintDocument.compile(nbt, registry).buildable(), "requested NBT never silently ignored");
        JsonObject entities = document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}");
        entities.add("entities", JsonParser.parseString("[{\"id\":\"create:super_glue\"}]"));
        check(!MachineBlueprintDocument.compile(entities, registry).buildable(), "unsupported entity assembly explicit");
        JsonObject door = document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:oak_door\",\"properties\":{\"half\":\"lower\"}}");
        check(!MachineConstructionPlan.reviewExplicit(MachineBlueprintDocument.compile(door, registry)).buildable(),
                "design review rejects undeclared generated half before construction");
        JsonObject water = document("{\"offset\":[0,0,0],\"block_id\":\"minecraft:water\"}");
        check(!MachineConstructionPlan.reviewExplicit(MachineBlueprintDocument.compile(water, registry)).buildable(),
                "design review rejects missing native fluid installation adapter");
        try {
            MachineConstructionPlan.compile(anchor, MachineBlueprintDocument.compile(door, registry), false);
            throw new AssertionError("undeclared generated door half accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("half"), "precise generated-cell diagnostic"); }
        JsonObject large = new JsonObject(); large.addProperty("schema_version", 1); JsonArray cells = new JsonArray();
        for (int i = 0; i < 600; i++) {
            JsonObject cell = new JsonObject(); JsonArray pos = new JsonArray(); pos.add(i % 30); pos.add(0); pos.add(i / 30);
            cell.add("offset", pos); cell.addProperty("block_id", "minecraft:stone"); cells.add(cell);
        }
        large.add("blocks", cells);
        check(MachineBlueprintDocument.compile(large, registry).buildable(), "explicit blueprints use configured planning budget, not old 512 limit");
    }
    private static JsonObject document(String cells) { return JsonParser.parseString("{\"schema_version\":1,\"blocks\":[" + cells + "]}").getAsJsonObject(); }
    private static void rejects(JsonObject value) {
        try { MachineBlueprintDocument.validateWire(value); throw new AssertionError("accepted malformed blueprint: " + value); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
