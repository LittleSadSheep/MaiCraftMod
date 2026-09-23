// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 从漏斗原生需求读取实际物品，并声明支承依赖；规则与加工产品无关。 */
public final class CreateFunnelPlacement {
    private static final String FUNNEL = "com.simibubi.create.content.logistics.funnel.FunnelBlock";
    private static final String VARIANT = "com.simibubi.create.content.logistics.funnel.BeltFunnelBlock";
    private static final String BELT = "com.simibubi.create.content.kinetics.belt.BeltBlock";
    private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
    private static final String DIRECT = "com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour";
    private CreateFunnelPlacement() {}
    public static boolean hasNativeItemTransition(BlockItem item) {
        return NativeApi.is(item, "com.simibubi.create.content.logistics.funnel.FunnelItem");
    }
    public static JsonObject describe(BlockState state) {
        if (!NativeApi.is(state.getBlock(), VARIANT) && !NativeApi.is(state.getBlock(), FUNNEL)) return null;
        JsonObject result = new JsonObject();
        // 普通漏斗和带上漏斗有不同状态字段，公开资料不能把最终变体的 shape 冒充普通漏斗属性。
        result.addProperty("belt_variant", NativeApi.is(state.getBlock(), VARIANT));
        result.addProperty("ordinary_funnel_state", "facing and extracting; shape only belongs to the belt-mounted variant");
        result.addProperty("operation", "native_block_item_placement"); result.addProperty("dependency_offset", "[0,-1,0] when horizontal or belt-mounted");
        result.addProperty("final_identity_required", true);
        try {
            BlockState variant = NativeApi.is(state.getBlock(), VARIANT) ? state : (BlockState) NativeApi.call(state.getBlock(), FUNNEL,
                    "getEquivalentBeltFunnel", new SupportView(Blocks.AIR.defaultBlockState()), BlockPos.ZERO, state.setValue(BlockStateProperties.FACING, Direction.NORTH));
            result.addProperty("supported_final_variant", BuiltInRegistries.BLOCK.getKey(variant.getBlock()).toString());
            result.addProperty("placement_item", BuiltInRegistries.ITEM.getKey(material(variant)).toString());
            result.addProperty("support_rule", "The block below must natively support belt funnels; placement runs after that support is installed. On supported surfaces declare the final variant, not the ordinary funnel ID.");
            result.addProperty("inventory_side", "opposite of facing");
            JsonArray required = new JsonArray(); required.add("facing"); required.add("shape"); result.add("final_variant_author_state", required);
            result.addProperty("final_variant_shape_rule", "Perpendicular to a belt: pulling receives into inventory, pushing feeds the belt. Along the belt: retracted. Extended needs a separate interaction and is not available through this placement operation.");
            result.addProperty("execution_verified", false);
        } catch (RuntimeException | LinkageError unavailable) { result.addProperty("unknown", "native_funnel_placement_contract_unavailable"); }
        return result;
    }

    public static BlockItem material(BlockState state) {
        return nativeRead(() -> materialNative(state));
    }
    private static BlockItem materialNative(BlockState state) {
        if (!NativeApi.is(state.getBlock(), VARIANT)) return null;
        // 原生配方声明消耗一个父漏斗物品，不能把没有独立物品的最终形态算作空气或直接写进世界。
        Object requirement = NativeApi.call(state.getBlock(), VARIANT, "getRequiredItems", state, null);
        Object raw = NativeApi.call(requirement, null, "getRequiredItems");
        if (!(raw instanceof List<?> rows) || rows.size() != 1) throw bad("native_funnel_material_requirement_unavailable");
        Object stack = NativeApi.field(rows.getFirst(), null, "stack"), use = NativeApi.field(rows.getFirst(), null, "usage");
        if (!(stack instanceof ItemStack item) || item.getCount() != 1 || !item.getComponentsPatch().isEmpty()
                || !use.toString().equals("CONSUME") || !(item.getItem() instanceof BlockItem block)
                || !NativeApi.is(block, "com.simibubi.create.content.logistics.funnel.FunnelItem")) throw bad("native_funnel_material_requirement_unavailable");
        return block;
    }
    public static List<BlockPos> dependencies(BlockState state) {
        Direction facing = Direction.byName(property(state, "facing"));
        return NativeApi.is(state.getBlock(), VARIANT) || NativeApi.is(state.getBlock(), FUNNEL) && facing != null && facing.getAxis().isHorizontal()
                ? List.of(new BlockPos(0, -1, 0)) : List.of();
    }
    public static void validate(BlockState state, BlockState support) {
        nativeRead(() -> { validateNative(state, support); return null; });
    }
    private static void validateNative(BlockState state, BlockState support) {
        if (dependencies(state).isEmpty()) return;
        boolean accepts = supports(support);
        if (!NativeApi.is(state.getBlock(), VARIANT)) {
            // 普通水平漏斗在原生承载面上会变形；要求作者声明最终形态，避免施工后永远等不到原方块 ID。
            if (accepts) {
                var transformed = (BlockState) NativeApi.call(state.getBlock(), FUNNEL, "getEquivalentBeltFunnel", new SupportView(support), BlockPos.ZERO, state);
                throw bad("native_funnel_final_state_required: " + BuiltInRegistries.BLOCK.getKey(transformed.getBlock()));
            }
            return;
        }
        if (!accepts) throw bad("native_funnel_support_missing");
        Direction facing = Direction.byName(property(state, "facing")); String desired = property(state, "shape");
        // 仅接受正常放置能得到的形态；额外扳手变形需要单独原生动作，不能伪装成一次放置已经完成。
        for (boolean extracting : List.of(false, true)) {
            Object shape = NativeApi.call(null, VARIANT, "getShapeForPosition", new SupportView(support), BlockPos.ZERO, facing, extracting);
            if (desired.equals(NativeApi.call(shape, null, "getSerializedName"))) return;
        }
        throw bad("native_funnel_shape_requires_separate_interaction: " + desired);
    }
    private static boolean supports(BlockState support) {
        if (NativeApi.is(support.getBlock(), BELT)) return NativeApi.truth(NativeApi.call(null, BELT, "canTransportObjects", support));
        if (!(support.getBlock() instanceof EntityBlock factory)) return false;
        // 与加工接口读取一样，只构造脱离世界的实体读取行为声明，不 initialize、tick 或操作库存。
        BlockEntity entity = factory.newBlockEntity(BlockPos.ZERO, support);
        if (!NativeApi.is(entity, SMART)) return false;
        Object behaviour = NativeApi.call(entity, SMART, "getBehaviour", NativeApi.constant(DIRECT, "TYPE"));
        return behaviour != null && NativeApi.truth(NativeApi.call(behaviour, DIRECT, "canSupportBeltFunnels"));
    }
    private static String property(BlockState state, String name) {
        var property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? "" : state.getValue(property).toString().toLowerCase(Locale.ROOT);
    }
    private record SupportView(BlockState support) implements BlockGetter {
        public BlockState getBlockState(BlockPos at) { return at.equals(BlockPos.ZERO.below()) ? support : Blocks.AIR.defaultBlockState(); }
        public BlockEntity getBlockEntity(BlockPos at) { return null; }
        public FluidState getFluidState(BlockPos at) { return getBlockState(at).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static IllegalArgumentException bad(String reason) { return new IllegalArgumentException(reason); }
    private static <T> T nativeRead(Supplier<T> read) {
        // 模组版本不匹配时让设计审阅明确不可建，不能把原生 API 异常遗漏成已验证的普通方块安装。
        try { return read.get(); }
        catch (IllegalArgumentException invalid) { throw invalid; }
        catch (RuntimeException | LinkageError unavailable) { throw new IllegalArgumentException("native_funnel_placement_contract_unavailable", unavailable); }
    }
}
