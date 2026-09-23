// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 只读原生连接许可、物品端点标记和整段带的同步状态；实际连接必须交给玩家右键。 */
public final class CreateBeltAccess {
    public static final ResourceLocation ITEM = ResourceLocation.parse("create:belt_connector");
    public static final ResourceLocation SHAFT = ResourceLocation.parse("create:shaft");
    private static final String CONNECTOR = "com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem";
    private static final String ENTITY = "com.simibubi.create.content.kinetics.belt.BeltBlockEntity";
    private static final ResourceLocation SELECTION = ResourceLocation.parse("create:belt_first_shaft");
    private CreateBeltAccess() {}

    public static boolean available() {
        return NativeApi.present(CONNECTOR) && NativeApi.present(ENTITY)
                && BuiltInRegistries.ITEM.containsKey(ITEM) && BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(SELECTION);
    }
    public static int maximumLength() {
        if (!available()) throw new IllegalArgumentException("create_belt_native_api_unavailable");
        return ((Number) NativeApi.call(null, CONNECTOR, "maxLength")).intValue();
    }
    public static boolean canConnect(Level world, CreateBeltGeometry.Span span) {
        return NativeApi.truth(NativeApi.call(null, CONNECTOR, "canConnect", world, span.first(), span.second()));
    }
    @SuppressWarnings("unchecked")
    public static BlockPos selection(ItemStack stack) {
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(SELECTION);
        if (type == null) throw new IllegalArgumentException("create_belt_selection_api_unavailable");
        Object selected = stack.get((DataComponentType<Object>) type);
        if (selected != null && !(selected instanceof BlockPos)) throw new IllegalArgumentException("create_belt_selection_type_changed");
        return (BlockPos) selected;
    }
    public static boolean connector(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ITEM); }
    public static boolean unselected(ItemStack stack) { return connector(stack) && stack.getComponentsPatch().isEmpty() && selection(stack) == null; }
    public static int count(LocalPlayer player) {
        return player.getInventory().items.stream().filter(CreateBeltAccess::connector).mapToInt(ItemStack::getCount).sum()
                + (connector(player.getOffhandItem()) ? player.getOffhandItem().getCount() : 0);
    }
    public static boolean plainShaft(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(SHAFT) && stack.getComponentsPatch().isEmpty(); }
    public static int shaftCount(LocalPlayer player) {
        return player.getInventory().items.stream().filter(CreateBeltAccess::plainShaft).mapToInt(ItemStack::getCount).sum()
                + (plainShaft(player.getOffhandItem()) ? player.getOffhandItem().getCount() : 0);
    }
    public static Set<BlockPos> pulleysToAdd(Level world, CreateBeltGeometry.Span span, Set<BlockPos> desired) {
        Set<BlockPos> current = new LinkedHashSet<>();
        for (BlockPos at : span.cells()) {
            if (!world.isLoaded(at)) return null;
            var state = world.getBlockState(at); var part = state.getBlock().getStateDefinition().getProperty("part");
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:belt") || part == null) return null;
            if (!propertyMatches(state, part, "middle")) current.add(at);
        }
        return pulleyChanges(desired, current, matches(world, span, current));
    }
    static Set<BlockPos> pulleyChanges(Set<BlockPos> desired, Set<BlockPos> current, boolean chainMatches) {
        // 只允许在同一条原生链上加轴，已有额外带轮和不同控制器都需要重新调查，绝不隐式拆除。
        if (!chainMatches || !desired.containsAll(current)) return null;
        Set<BlockPos> remaining = new LinkedHashSet<>(desired); remaining.removeAll(current); return Set.copyOf(remaining);
    }
    public static boolean shaft(BlockState state, CreateBeltGeometry.Span span) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:shaft")
                && state.hasProperty(BlockStateProperties.AXIS) && state.getValue(BlockStateProperties.AXIS) == span.axis();
    }
    public static boolean matches(Level world, CreateBeltGeometry.Span span, Set<BlockPos> pulleys) {
        // 每格方块、控制器、带长与索引都要一致，不能凭两端看起来连上就确认整段成功。
        for (int index = 0; index < span.cells().size(); index++) {
            BlockPos at = span.cells().get(index);
            if (!world.isLoaded(at)) return false;
            var state = world.getBlockState(at);
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:belt")) return false;
            for (var expected : span.properties(at, pulleys.contains(at)).entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(expected.getKey());
                if (property == null || !propertyMatches(state, property, expected.getValue())) return false;
            }
            var entity = world.getBlockEntity(at);
            if (!NativeApi.is(entity, ENTITY) || !span.first().equals(NativeApi.call(entity, ENTITY, "getController"))
                    || NativeApi.number(NativeApi.field(entity, ENTITY, "beltLength")) != span.cells().size()
                    || NativeApi.number(NativeApi.field(entity, ENTITY, "index")) != index) return false;
        }
        return true;
    }
    private static <T extends Comparable<T>> boolean propertyMatches(BlockState state, Property<T> property, String expected) {
        return property.getName(state.getValue(property)).equals(expected);
    }
}
