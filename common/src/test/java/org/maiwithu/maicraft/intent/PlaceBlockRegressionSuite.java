// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/**
 * place_block 只做一次翻译：合成 1 格蓝图交回普通施工链路，勘察、站位、落定与验证全部继承。
 * build 专属参数、实体摆设、非方块 ID、模糊目标与未加载坐标都必须在翻译层被拦下并指对路，
 * 不能漏进施工流程后才以含糊的失败告终。
 */
public final class PlaceBlockRegressionSuite {
    private static final BlockPos TARGET = new BlockPos(2, 1, 4);
    private static final BlockPos UNLOADED = new BlockPos(32, 1, 4);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        synthesizesSingleCellBuild();
        rejectsMisdirectedGoals();
        System.out.println("PlaceBlockRegressionSuite: passed");
    }

    // 翻译纯度：产物就是一次普通单格施工——1 个 set op、随身供料、替换授权与状态要求原样透传。
    private static void synthesizesSingleCellBuild() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.CHEST));
            var goal = goalAt(TARGET, "coordinates",
                    blockParams("minecraft:chest", true, "facing", "north"));
            SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
            var action = AbilityAdapter.adapt(goal, h.player, null);
            check(action instanceof IntentAction.Tool tool && "build".equals(tool.toolName()),
                    "place_block translates into the ordinary build tool, not a second placement path");
            JsonObject args = ((IntentAction.Tool) action).arguments();
            check("inventory_only".equals(args.get("material_policy").getAsString())
                    && args.get("replace_existing").getAsBoolean() && args.get("exact_states").getAsBoolean(),
                    "synthesis pins carried-item supply and keeps the replacement permission and exact states");
            var ops = args.getAsJsonArray("ops");
            check(ops != null && ops.size() == 1, "synthesis produces exactly one placement op");
            var op = ops.get(0).getAsJsonObject();
            check("minecraft:chest".equals(op.get("block_id").getAsString())
                    && op.get("x").getAsInt() == TARGET.getX() && op.get("y").getAsInt() == TARGET.getY()
                    && op.get("z").getAsInt() == TARGET.getZ()
                    && "north".equals(op.getAsJsonObject("properties").get("facing").getAsString()),
                    "the single op sits at the requested position with its final state requirements");
        }
    }

    // 错误的指路在计划期或翻译层就被拦下；模型看到的是下一步该用什么能力，而不是施工中途的失败。
    private static void rejectsMisdirectedGoals() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // build 专属参数混入：计划期被通用未知参数白名单拒绝，适配期指回 maicraft:build。
            JsonObject mixed = blockParams("minecraft:chest", null);
            mixed.add("blueprint", new JsonObject());
            var violation = assertViolation(() -> SemanticGoalContract.validate(
                    goalAt(TARGET, "coordinates", mixed), GeneralAbilityAdapter.abilities()));
            check("unknown_parameter".equals(violation.violationCode())
                    && violation.getMessage().contains("blueprint"),
                    "plan-time validation rejects the build-only parameter before any synthesis");
            var refused = decision(AbilityAdapter.adapt(goalAt(TARGET, "coordinates", mixed), h.player, null));
            check(refused.snapshot().question().contains("belongs to maicraft:build"),
                    "adapter decision points back at maicraft:build for build-only parameters");

            // 实体摆设不是方块：拒绝并如实说明 LLM 蓝图不安装实体，摆设只随结构文件落地。
            var frame = decision(AbilityAdapter.adapt(goalAt(TARGET, "coordinates",
                    blockParams("minecraft:item_frame", null)), h.player, null));
            check(frame.snapshot().question().contains("display entity")
                    && frame.snapshot().question().contains("structure files"),
                    "display entities are refused with the honest structural-file pointer");

            // 未安装的方块 ID 同样在翻译层拒绝。
            decision(AbilityAdapter.adapt(goalAt(TARGET, "coordinates",
                    blockParams("minecraft:not_a_block", null)), h.player, null));

            // 非坐标目标（例如 current_place）不是原子放置的合法表达，不能悄悄变成就近放置。
            var vague = decision(AbilityAdapter.adapt(goalAt(TARGET, "current_place",
                    blockParams("minecraft:chest", null)), h.player, null));
            check(vague.snapshot().question().contains("exact coordinates"),
                    "vague targets cannot silently become nearby placements");

            // 目标未加载：缺席不等于存在，不就近替换。
            var unloaded = decision(AbilityAdapter.adapt(goalAt(UNLOADED, "coordinates",
                    blockParams("minecraft:chest", null)), h.player, null));
            check(unloaded.snapshot().question().contains("not loaded"),
                    "unloaded targets stay unknown instead of substituting a loaded cell");

            // 方块状态要求是终态要求：不存在的状态值在翻译层就失败，不偷偷放上默认状态。
            var goal = goalAt(TARGET, "coordinates", blockParams("minecraft:chest", null, "facing", "sideways"));
            SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
            try {
                AbilityAdapter.adapt(goal, h.player, null);
                throw new AssertionError("unsupported block-state requirement must fail at translation");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static Goal goalAt(BlockPos pos, String targetKind, JsonObject parameters) {
        return new Goal(GeneralAbilityAdapter.PLACE_BLOCK, "set down one block",
                new Goal.SemanticTarget(targetKind, null,
                        "coordinates".equals(targetKind)
                                ? new Goal.WorldPosition(pos.getX(), pos.getY(), pos.getZ(), "minecraft:overworld")
                                : null,
                        null),
                parameters.toString(), "{}", List.of(), List.of());
    }

    private static JsonObject blockParams(String blockId, Boolean replaceExisting, String... property) {
        JsonObject p = new JsonObject();
        if (blockId != null) p.addProperty("block_id", blockId);
        if (property.length > 0) {
            JsonObject properties = new JsonObject();
            for (int i = 0; i + 1 < property.length; i += 2) properties.addProperty(property[i], property[i + 1]);
            p.add("properties", properties);
        }
        if (replaceExisting != null) p.addProperty("replace_existing", replaceExisting);
        return p;
    }

    private static IntentAction.Decision decision(IntentAction action) {
        check(action instanceof IntentAction.Decision,
                "expected a paused decision, got " + action.getClass().getSimpleName());
        return (IntentAction.Decision) action;
    }

    private static SemanticContractException assertViolation(Runnable attempt) {
        try { attempt.run(); }
        catch (SemanticContractException expected) { return expected; }
        throw new AssertionError("expected a semantic contract violation");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
