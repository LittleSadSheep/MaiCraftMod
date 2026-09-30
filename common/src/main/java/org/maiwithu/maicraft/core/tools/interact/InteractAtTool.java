package org.maiwithu.maicraft.core.tools.interact;
import org.maiwithu.maicraft.core.tools.BlockActionOps;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 接收内部 interact_at 请求，交给 BlockActionOps 检查参数并建立原地点击任务；坐标、物品和结果要求原样传下去。
 */
public final class InteractAtTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final BlockActionOps impl = new BlockActionOps();

    private record Args(String button, Integer x, Integer y, Integer z, Integer hold_ticks,
                        String item_id, String expected_block_id, String required_block_id, Boolean empty_hand,
                        String item_resource_id, Boolean approach, Boolean may_alter_terrain) {}

    @Override
    public String name() {
        return "interact_at";
    }

    @Override
    public String description() {
        return "Aim at a world point and press one mouse button — the full native click for BLOCKS, "
                + "FLUIDS and the AIR (moving entities use interact_entity). right = use/place/activate; "
                + "if the aimed block doesn't respond, the held item acts on its own, exactly like a real "
                + "right-click — so aiming at WATER with a bucket scoops it, with a boat places it. "
                + "left = attack/break (prefer mine for digging). The result reports what actually "
                + "changed (hands, aimed block, new entities); no change listed = the click did nothing. "
                + "By default it acts within current reach. approach=true lets the native executor choose "
                + "a reachable interaction stance; terrain changes require may_alter_terrain=true.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("button", "right = use/activate/throw, left = attack/break.", "left", "right")
                .nullableInteger("x", "Aim X. Null (with y,z null) = use the held item straight ahead (eat/drink).")
                .nullableInteger("y", "Aim Y. Null when aiming forward.")
                .nullableInteger("z", "Aim Z. Null when aiming forward.")
                .nullableInteger("hold_ticks", "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout.")
                .nullableString("item_id", "Optional namespaced item to equip-and-use, e.g. minecraft:bonemeal. Null = use what's in hand.")
                .nullableString("item_resource_id", "Optional observed component-sensitive identity; requires item_id and block use. Missing or changed identity stops before use.")
                .nullableString("expected_block_id", "Optional required resulting block at the aim; an ineffective click is not success.")
                .nullableString("required_block_id", "Optional block identity that must still occupy the aim immediately before native use.")
                .optionalBool("empty_hand", "Prepare an empty main hand before block use; incompatible with item_id. Omitted or false retains held-item use.")
                .optionalBool("approach", "Choose and reach a visible interaction stance before the native click.")
                .optionalBool("may_alter_terrain", "Allow native terrain preparation only when approach is enabled.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        var task = (InteractAtTaskRecord) impl.interactAt(a.button(), a.x(), a.y(), a.z(), a.hold_ticks(), a.item_id(),
                a.expected_block_id(), a.required_block_id(), ctx(toolCallId, companion));
        // 空手要求随内部任务传到底层，导航或自动反击更换了主手也不能丢失这份操作意图。
        if (Boolean.TRUE.equals(a.empty_hand())) task.withEmptyHand();
        // 把语义层选定的工件身份一路带到原生选物前，不在内部解析时丢掉装配进度约束。
        if (a.item_resource_id() != null) task.withItemResourceId(a.item_resource_id());
        // 上层只给目标与地形许可，站位和换路留给同一原生任务，避免把寻路失败退给模型手动拆步。
        if (Boolean.TRUE.equals(a.approach())) task.withApproach(Boolean.TRUE.equals(a.may_alter_terrain()));
        runSync(companion, task, reply);
    }
}
