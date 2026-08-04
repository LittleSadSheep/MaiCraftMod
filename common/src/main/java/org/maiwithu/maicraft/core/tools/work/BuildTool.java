package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.core.build.BuildPalette;
import org.maiwithu.maicraft.core.build.BuildShapes;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTraversabilityContract;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Build a bounded set of explicit block cells as one background task. */
public final class BuildTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final long TRAVEL_ALLOWANCE_TICKS = 40 * 20;
    /** 施工预计时长之上再留的余量(挪窝、翻层停顿、零进展重试都吃这笔)。 */
    private static final double TIMEOUT_SLACK = 1.6;

    /**
     * 施工时限:赴工地的行程 + 施工预计时长再留一截余量。
     *
     * <p>预计时长必须问施工层要,不能在这里另估一套。此前这里按"每格固定几刻"
     * 拍了个数,而生存最慢档实际是每格十刻——差二十倍,五百格的房子会在盖到一半
     * 时被判超时。两处各拍各的迟早再犯,所以公式只有一处真源。
     */
    public static long timeoutTicksFor(int cellCount, boolean consumeMaterials) {
        long build = org.maiwithu.maicraft.core.task.build.BuildOrder
                .estimatedTicks(cellCount, consumeMaterials);
        return Math.max(MIN_TIMEOUT_TICKS,
                TRAVEL_ALLOWANCE_TICKS + (long) (build * TIMEOUT_SLACK));
    }

    private record Args(List<OpSpec> ops, Boolean replace_existing, Boolean allow_partial,
                        Map<String, Object> semantic_contract,
                        BuildTraversabilityContract traversability_contract) {}
    private record OpSpec(String op, String block_id, Boolean hollow,
                          Integer x, Integer y, Integer z,
                          Integer x1, Integer y1, Integer z1,
                          Integer x2, Integer y2, Integer z2,
                          Integer radius, Integer height, Double density,
                          String facing, String axis, String half,
                          Integer overhang, String gable_block, String ridge_block,
                          String eave_block, String soffit_block,
                          String roof_shape, String roof_curve, Integer corner_lift,
                          Map<String, String> properties) {}
    private record BlockSpec(String block_id, int x, int y, int z,
                             String facing, String axis, String half,
                             Map<String, String> properties) {}

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Construct or clear blocks as ONE background task, expressed as a single ordered `ops` stream — "
                + "write ops in build order, later ops overwrite earlier cells. Ops: `set` one cell (block_id, x,y,z, "
                + "optional facing/axis/half/properties — precise details like stairs and torches; a bare "
                + "set with none of those is placed like a real player right-click: it faces her naturally "
                + "and mods hooking item placement apply); `box` "
                + "corners x1,y1,z1..x2,y2,z2 (hollow = outer shell); `walls` vertical perimeter ring only, NO "
                + "top/bottom face — the right primitive for wall rings; `line` two points; `cylinder` bottom-"
                + "center x1,y1,z1 + radius + height (hollow = tube); `sphere` center + radius (hollow = shell); "
                + "`roof` over base rect x1,y1,z1..x2,z2 — give a SLAB block as block_id (stone_brick_slab, "
                + "deepslate_tile_slab, spruce_slab...) and it lays a real tiled slope: the surface climbs HALF "
                + "a block per cell so there are no full-block steps, shallow at the eaves and steepening toward "
                + "the ridge. Slabs, not stairs, are what a good roof is made of. Then `roof_shape` (xuanshan / "
                + "wudian / xieshan / zuanjian / shed), `overhang` 1-4, `ridge_block` for the ridges that stand "
                + "proud of the tiles, `eave_block` for the drip band around the edge, `gable_block` for the end "
                + "walls, `soffit_block` for the underside, `corner_lift` for upturned corners, `hollow` "
                + "(default true, leaves attic space); `set_door` a full door at x,y,z (lower "
                + "half) with `facing`; `scatter` sprinkles the block over plane y1 within x1,z1..x2,z2 at "
                + "optional `density` 0-1 (default 0.25) — flowers, grass, mushrooms. "
                + "PALETTES: any block_id may name a weighted MIX instead of one block — "
                + "\"stone_bricks*8, mossy_stone_bricks*2, cracked_stone_bricks\" — and each cell picks one "
                + "deterministically. A flat single-colour surface is the number one thing that makes a build "
                + "look fake, so mix 10-20% of a weathered variant into every large wall, floor and roof. "
                + "block_id `air` CLEARS the cell (drops harvest normally). Liquids are NOT handled: leave "
                + "water and lava out of the ops — dig the basin and let the player pour it. "
                + "Compose whole buildings like stacking toy bricks in ONE call, "
                + "up to 16384 cells — enough for a whole house, so use it. She walks to the site once, then "
                + "works inside it, placing cells in batches "
                + "from the ground up. MATERIALS: in creative she builds freely. In survival every cell consumes "
                + "1 matching item and the whole job is refused up front, PLACING NOTHING, if anything is "
                + "short — so put the ENTIRE building (foundation, walls, roof, openings, details) in ONE call. "
                + "Split it across several calls and the walls go up before anyone discovers the roof material "
                + "is missing, leaving a half-built shell. Treat a shortfall as an invitation to gather "
                + "together, not an error, and never promise the player a survival build costs nothing. "
                + "The internal allow_partial flag is only for a semantic orchestrator that has retained the "
                + "whole reviewed plan and already arranged recoverable resupply; it stops honestly with "
                + "completed and missing-material evidence when the carried batch runs out. "
                + "For prebuilt structure files use the blueprint tool. BACKGROUND: after acceptance wait for "
                + "task_finished; never resend while running or after status=done.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("op", Map.of("type", "string",
                "enum", List.of("set", "box", "walls", "line", "cylinder", "sphere",
                        "roof", "set_door", "scatter")));
        props.put("block_id", Map.of("type", "string",
                "description", "Block for this op, e.g. minecraft:stone_bricks; minecraft:air clears. "
                        + "May be a weighted mix — \"stone_bricks*8, mossy_stone_bricks*2, "
                        + "cracked_stone_bricks\" — each cell picks one deterministically. Mix a weathered "
                        + "variant into every large surface; flat single-colour walls read as fake."));
        props.put("hollow", Map.of("type", "boolean",
                "description", "box/cylinder/sphere: keep only the outer shell. "
                        + "roof: leave the space under the slopes open (default true)."));
        props.put("overhang", Map.of("type", "integer",
                "description", "roof: eaves extending past the walls, 0-4. 1-2 reads far better than 0; "
                        + "East Asian roofs want 2-4."));
        props.put("roof_shape", enumSchema(
                "roof: xuanshan (alias gable) = two slopes with a ridge along the longer axis, the two ends "
                        + "closed by a bargeboard; the everyday roof, Chinese and Western alike. "
                        + "wudian (alias hip) = four slopes with four diagonal hip ridges and no gable ends — "
                        + "the highest rank, save it for the grandest hall on a site. "
                        + "zuanjian (alias pyramid) = the four slopes meet at a point, for a square footprint: "
                        + "towers, gazebos, pavilions. "
                        + "xieshan (alias half_hip) = four slopes below, a gable with two decorated ends above; "
                        + "second in rank and the most ornate silhouette. "
                        + "shed = a single slope one way, for lean-tos, porches and anything added on. "
                        + "Default xuanshan.",
                "xuanshan", "wudian", "xieshan", "zuanjian", "shed",
                "gable", "hip", "half_hip", "pyramid"));
        props.put("roof_curve", enumSchema(
                "roof: concave (default) = shallow at the eaves and steepening toward the ridge. This is the "
                        + "real profile of a tiled roof, East Asian or otherwise, and it is what stops a roof "
                        + "reading as a stepped pyramid. straight = constant 1:1 pitch; only ask for it when you "
                        + "specifically want a steep, hard, Gothic or Alpine silhouette.",
                "concave", "straight"));
        props.put("corner_lift", Map.of("type", "integer",
                "description", "roof: flick the four eave corners upward by this many blocks, 0-3. The "
                        + "upturned corner is the single most recognisable feature of East Asian roofs; "
                        + "leave it 0 for Western ones."));
        props.put("gable_block", Map.of("type", "string",
                "description", "roof: block (or mix) filling the end walls — the triangle under a xuanshan "
                        + "slope, or the decorated panel of a xieshan. Omit and they stay open."));
        props.put("ridge_block", Map.of("type", "string",
                "description", "roof: block (or mix) for the RIDGES, which stand proud of the tiles — the "
                        + "crest along the top, the four diagonal hip ridges of a wudian/zanjian, and the "
                        + "bargeboards of a xuanshan. Pick something that contrasts with the roof: this one "
                        + "line is most of what makes a roof read as designed rather than extruded."));
        props.put("eave_block", Map.of("type", "string",
                "description", "roof: block (or mix) for the outermost course only — the drip band running "
                        + "right around the edge. A single contrasting ring (copper, dark prismarine, a "
                        + "different wood) does an enormous amount of work for one block of width."));
        props.put("soffit_block", Map.of("type", "string",
                "description", "roof: block (or mix) for a second skin one block under the tiles, following "
                        + "the same slope. This is what the roof looks like FROM BELOW and from an open gable; "
                        + "without it the underside is bare half-slabs and the roof reads as a shell. Costs "
                        + "roughly as many cells again, so skip it on roofs nobody will stand under."));
        props.put("x", Map.of("type", "integer", "description", "set/set_door: cell x."));
        props.put("y", Map.of("type", "integer", "description", "set/set_door: cell y (door lower half)."));
        props.put("z", Map.of("type", "integer", "description", "set/set_door: cell z."));
        // 哪个 op 要哪几个坐标,此前一个字都没写,模型只能从散文里猜——实测就撞出
        // 过 "y2 is required"。每一条都点名用它的 op。
        props.put("x1", Map.of("type", "integer",
                "description", "First corner X. box/walls/line/scatter: the rect. cylinder: bottom "
                        + "centre. sphere: centre. roof: base rect."));
        props.put("y1", Map.of("type", "integer",
                "description", "First corner Y. cylinder: bottom level. scatter: the single plane it "
                        + "sprinkles on. roof: the level the eaves sit at."));
        props.put("z1", Map.of("type", "integer", "description", "First corner Z — see x1."));
        props.put("x2", Map.of("type", "integer",
                "description", "Opposite corner X. Needed by box/walls/line/scatter/roof."));
        props.put("y2", Map.of("type", "integer",
                "description", "Opposite corner Y — REQUIRED by box, walls and line, which are "
                        + "volumes. NOT used by scatter (one plane, y1) or roof (height is derived "
                        + "from the span)."));
        props.put("z2", Map.of("type", "integer",
                "description", "Opposite corner Z. Needed by box/walls/line/scatter/roof."));
        props.put("radius", Map.of("type", "integer",
                "description", "cylinder/sphere only: radius in blocks."));
        props.put("height", Map.of("type", "integer",
                "description", "cylinder only: height in blocks, upward from y1."));
        props.put("density", Map.of("type", "number",
                "description", "scatter: fraction of cells to fill, 0-1, default 0.25."));
        props.put("facing", enumSchema("set: block facing; set_door: which way the door faces.",
                "north", "south", "east", "west", "up", "down"));
        props.put("axis", enumSchema("set: pillar/log axis.", "x", "y", "z"));
        props.put("half", enumSchema("set: slab/stair half.", "top", "bottom"));
        props.put("properties", Map.of(
                "type", List.of("object", "null"),
                "description", "set: extra block-state properties by name.",
                "additionalProperties", Map.of("type", "string")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", props);
        item.put("required", List.of("op", "block_id"));
        item.put("additionalProperties", false);

        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("type", "array");
        ops.put("description", "Ordered build instructions; later ops overwrite earlier cells.");
        ops.put("items", item);
        ops.put("minItems", 1);

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("ops", ops);
        rootProps.put("replace_existing", Map.of(
                "type", "boolean",
                "description", "Optional, default true. Clear wrong non-protected blocks at requested cells."));
        rootProps.put("allow_partial", Map.of(
                "type", "boolean",
                "description", "Internal batching boundary, default false. Set true only when an upper "
                        + "semantic task retains this complete plan and has already verified a recoverable "
                        + "resupply path; the task reports exact completed and missing-material evidence."));
        rootProps.put("semantic_contract", Map.of(
                "type", List.of("object", "null"),
                "description", "Internal planner-authored aggregate design facts. Never accepts or returns cells.",
                "additionalProperties", true));
        rootProps.put("traversability_contract", Map.of(
                "type", List.of("object", "null"),
                "description", "Internal planner-authored route endpoints; the finished client world is scanned read-only.",
                "additionalProperties", true));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of("ops"));
        root.put("additionalProperties", false);
        return root;
    }

    private static Map<String, Object> enumSchema(String description, String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        schema.put("description", description);
        schema.put("enum", valuesWithNull(values));
        return schema;
    }

    private static List<Object> valuesWithNull(String... values) {
        List<Object> out = new ArrayList<>();
        for (String value : values) {
            out.add(value);
        }
        out.add(null);
        return out;
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed.ops() == null || parsed.ops().isEmpty()) {
            throw new IllegalArgumentException("ops must contain at least one instruction");
        }
        List<BuildTaskRecord.Target> targets = resolveTargets(parsed.ops());
        if (targets.size() > BuildShapes.MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("this call resolves to " + targets.size()
                    + " cells, exceeding " + BuildShapes.MAX_TOTAL_CELLS + "; split it into multiple calls");
        }
        boolean replaceExisting = parsed.replace_existing() == null || parsed.replace_existing();
        boolean allowPartial = parsed.allow_partial() != null && parsed.allow_partial();
        // 材料记账随能力画像:免耗材(创造)想建就建;否则消耗背包,开工前
        // 由任务预检并逐项报缺(见 BuildCompanionTask 的 checkMaterials)。
        boolean consume = !org.maiwithu.maicraft.core.WorkProfile.of(companion).freeMaterials();
        long timeout = timeoutTicksFor(targets.size(), consume);
        BuildTaskRecord plan = new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets, replaceExisting,
                consume, allowPartial);
        plan.semanticFacts(parsed.semantic_contract());
        plan.traversabilityContract(parsed.traversability_contract());
        if (consume && allowPartial) {
            org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord
                    .ensureRegistered();
            long supplyTimeout = Math.max(timeout, 45L * 60L * 20L);
            setTask(companion,
                    new org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord(
                            toolCallId, ctx(toolCallId, companion).deadline(supplyTimeout), plan),
                    args, reply);
        } else {
            setTask(companion, plan, args, reply);
        }
