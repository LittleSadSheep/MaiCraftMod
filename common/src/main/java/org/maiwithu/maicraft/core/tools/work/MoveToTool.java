package org.maiwithu.maicraft.core.tools.work;
import org.maiwithu.maicraft.core.tools.MovementOps;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 内部 goto 入口：解析坐标、方块种类和移动许可，交给 MovementOps 创建任务单，再由父任务或调度器执行。
 * description 与 parameterSchema 保留旧内部工具说明；公开移动能力由语义能力目录定义。
 */
public final class MoveToTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final MovementOps impl = new MovementOps();

    private record Args(Double x, Double y, Double z, String block, Boolean may_alter_terrain,
                        Boolean allow_water_bucket_fall, String transport_mode, Boolean allow_landing_assists,
                        Boolean exact, Double horizontal_radius, Double vertical_tolerance) {}

    @Override
    public String name() {
        return "goto";
    }

    /** 常驻:移动是几乎每个任务的第一步。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return """
                Travel to ONE new destination with full terrain pathfinding: walks, jumps, swims, climbs, opens doors and gates, parkours, and auto-equips tools. Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — reach nearby ground within horizontal_radius (default 3). Omit y when the destination height is unknown; MaiCraft chooses a reachable height.
                • block — e.g. block:'minecraft:crafting_table'. Walks up BESIDE the one she can reach most easily and never damages it. Easiest to reach is not always closest in a straight line, so it may not be the first block scan_blocks listed — give coordinates when it has to be a specific one.
                • x+y+z — reach nearby ground with a height hint, within horizontal_radius (default 3) and vertical_tolerance (default 2). Keep a known height to distinguish floors; it does not require standing in one exact cell.
                • exact=true with x+y+z — stand in that exact cell. Use only when precise standing matters, never for the occupied cell of a machine you want to use.
                • y — climb/descend to that specified elevation.
                TERRAIN: by default the walk never breaks or places a block — walls, floors, other people's builds and the landscape stay exactly as they were. If the only route would need digging, bridging or pillaring, the call FAILS and lists exactly which blocks that route would break or place; read the list (planks/glass/bricks near the surface are usually someone's build; stone/dirt underground usually are not) and, if altering them is acceptable, re-send the SAME call with may_alter_terrain=true. Underground travel and climbing out of pits usually need it. Every call reports what it actually broke or placed.
                VEHICLES: start a goto while sitting in a boat (see <riding>) and she pilots it over the water toward the target — a destination on the water keeps her aboard, a destination ashore has her step off at the shore and finish on foot. Any other vehicle is stepped off the moment walking begins. Boarding is interact_entity right on the boat.
                BACKGROUND: a successful call means movement is already running. Do not call goto again or launch another body action while <current_task> exists; wait for matching task_finished. status=done means that destination is complete, so advance the plan and never resend identical coordinates. Only status=timeout permits the same call to resume.""";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X. Null for an elevation-only move (y alone).")
                .nullableNumber("y", "Destination height hint for x+y+z, checked within vertical_tolerance; y alone requests that specified elevation. Leave null only if height is unknown. Required for exact=true.")
                .nullableNumber("z", "Target Z. Null for an elevation-only move (y alone).")
                .optionalBool("exact", "Require the exact x/y/z cell. Default false permits a nearby reachable arrival; all three coordinates are required when true.")
                .optionalNumber("horizontal_radius", "Arrival radius in X/Z blocks; default 3. Ignored when exact=true.", 0)
                .optionalNumber("vertical_tolerance", "Allowed difference from the Y hint of x+y+z in blocks; default 2. Unknown Y is unrestricted. Ignored for exact=true or y alone.", 0)
                .optionalString("block", "Namespaced block id to walk up BESIDE (e.g. 'crafting_table' "
                        + "or 'minecraft:chest') — never broken or buried. Give it ALONE (no coordinates); "
                        + "she picks the one easiest to reach. ALWAYS use this form for a block you intend "
                        + "to use or mine.")
                .optionalBool("may_alter_terrain", "Consent to dig through, bridge or pillar on the way. "
                        + "Omit/false = leave every block untouched (default). Set true only after a failed "
                        + "goto listed the blocks a route would alter and you judge that acceptable, or "
                        + "when you already know the way is underground/through natural terrain.")
                .optionalBool("allow_water_bucket_fall", "Allow temporary bucket water for falls without allowing digging or scaffold placement. Requires a carried water bucket; default false.")
                .optionalBool("allow_landing_assists", "Allow verified temporary water, boats or landing blocks from carried items during falls, with recovery where supported. Does not permit excavation or scaffolding; default false.")
                .optionalEnum("transport_mode", "Travel mode: auto selects available native transport, ground uses ordinary navigation, jetpack requires usable Create jetpack equipment, elevator requires a usable Create elevator. Default auto; no mode grants terrain alteration.",
                        "auto", "ground", "jetpack", "elevator")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.moveTo(a.x(), a.y(), a.z(),
                a.block(), a.may_alter_terrain(), a.allow_water_bucket_fall(), a.transport_mode(), a.allow_landing_assists(),
                a.exact(), a.horizontal_radius(), a.vertical_tolerance(), ctx(toolCallId, companion)), args, reply);
    }
}
