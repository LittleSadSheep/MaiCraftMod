// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 当前主要负责 AE2 的电缆和面板：识别物品占哪个槽、预判右键会装到哪里、读取已经装好的部件。
 * 这里的普通方块 block/BlockSpec 帮助代码目前仅被测试使用；机器主施工使用 MachineConstructionPlan 和建造任务。
 */
public final class MachineInstallation {
    private MachineInstallation() {}

    public record BlockSpec(BlockState state, Item item, Set<String> exactProperties) {
        public BlockSpec { exactProperties = Set.copyOf(exactProperties); }
    }

    /**
     * side 为空代表方块中央的电缆；面板等外围部件必须指定东、西、南、北、上、下的一面。
     */
    public record PartSpec(Item item, Class<?> partClass, Direction side) {
        public PartSpec { Objects.requireNonNull(item); Objects.requireNonNull(partClass); }
        public String itemId() { return BuiltInRegistries.ITEM.getKey(item).toString(); }
    }

    public static BlockSpec block(String blockId, Direction facing, Direction.Axis axis) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw unsupported("unknown block " + blockId);
        var block = BuiltInRegistries.BLOCK.get(id);
        if (!(block.asItem() instanceof BlockItem item) || item.getBlock() != block) {
            throw unsupported("block has no matching native BlockItem: " + blockId);
        }
        BlockState state = block.defaultBlockState();
        Set<String> exact = new LinkedHashSet<>();
        if (facing != null) {
            state = property(state, "facing", facing.getSerializedName());
            exact.add("facing");
        }
        if (axis != null) {
            state = property(state, "axis", axis.getSerializedName());
            exact.add("axis");
        }
        return new BlockSpec(state, item, exact);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value)
                .orElseThrow(() -> unsupported("unsupported " + property.getName() + " value " + value)));
    }

    private static BlockState property(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) throw unsupported("installed block has no " + name + " property");
        return set(state, property, value);
    }

    // 从已安装的 AE2 接口识别物品；电缆与面板的占位不同，不能把“装在中央”和“装在某一面”混用。
    public static PartSpec aePart(String itemId, Direction side) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw unsupported("unknown item " + itemId);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) throw unsupported("air is not an AE2 part");
        try {
            Class<?> api = Class.forName("appeng.api.parts.IPartItem");
            if (!api.isInstance(item)) throw unsupported("item does not implement AE2 IPartItem");
            Class<?> part = (Class<?>) api.getMethod("getPartClass").invoke(item);
            boolean cable = Class.forName("appeng.api.parts.ICablePart").isAssignableFrom(part);
            if (cable != (side == null)) throw unsupported("cables occupy center; other AE2 parts require a face");
            return new PartSpec(item, part, side);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw unsupported("installed AE2 part API unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * 询问 AE2：“从这里右键会把部件装到哪一格、哪一面？”预测符合目标才允许继续；这一步不安装部件。
     */
    public static boolean predicts(LocalPlayer player, PartSpec spec, BlockPos target, BlockHitResult hit) {
        try {
            Class<?> placement = Class.forName("appeng.parts.PartPlacement");
            Object predicted = placement.getMethod("getPartPlacement", net.minecraft.world.entity.player.Player.class,
                    Level.class, ItemStack.class, BlockPos.class, Direction.class, net.minecraft.world.phys.Vec3.class)
                    .invoke(null, player, player.level(), new ItemStack(spec.item()), hit.getBlockPos(),
                            hit.getDirection(), hit.getLocation());
            if (predicted == null) return false;
            Object position = predicted.getClass().getMethod("pos").invoke(predicted);
            Object side = predicted.getClass().getMethod("side").invoke(predicted);
            return target.equals(position) && (spec.side() == null || spec.side() == side);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return false;
        }
    }

    /**
     * 既比较部件种类，也比较对应物品；例如两种颜色的电缆可能使用同一个类，不能因此视为相同电缆。
     */
    public static boolean matches(Level level, BlockPos target, PartSpec spec) {
        Object part = part(level, target, spec.side());
        if (part == null || !spec.partClass().isInstance(part)) return false;
        try {
            Class<?> api = Class.forName("appeng.api.parts.IPart");
            Object item = api.getMethod("getPartItem").invoke(part);
            return item == spec.item();
        } catch (ReflectiveOperationException | LinkageError failure) { return false; }
    }

    /**
     * 检查这一格能否接纳指定部件。空气里只允许先放中央电缆，已有宿主则询问 AE2 是否能占用该空槽。
     */
    public static boolean canInstall(Level level, BlockPos target, PartSpec spec) {
        if (!level.isLoaded(target) || level.isOutsideBuildHeight(target) || !level.getWorldBorder().isWithinBounds(target)) return false;
        if (matches(level, target, spec)) return true;
        if (level.getBlockState(target).isAir()) return spec.side() == null;
        try {
            Class<?> hostApi = Class.forName("appeng.api.parts.IPartHost");
            Object host = level.getBlockEntity(target);
            if (!hostApi.isInstance(host) || hostApi.getMethod("getPart", Direction.class).invoke(host, spec.side()) != null) return false;
            return (Boolean) hostApi.getMethod("canAddPart", ItemStack.class, Direction.class)
                    .invoke(host, new ItemStack(spec.item()), spec.side());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return false; }
    }

    static Object part(Level level, BlockPos target, Direction side) {
        if (!level.isLoaded(target)) return null;
        try {
            Class<?> host = Class.forName("appeng.api.parts.IPartHost");
            Object entity = level.getBlockEntity(target);
            return host.isInstance(entity) ? host.getMethod("getPart", Direction.class).invoke(entity, side) : null;
        } catch (ReflectiveOperationException | LinkageError failure) { return null; }
    }

    /**
     * 保存中央及六个面的部件对象，空槽也保留；确认安装时用它检查其他槽有没有被替换。
     */
    static Object[] parts(Level level, BlockPos target) {
        Object[] result = new Object[7];
        result[0] = part(level, target, null);
        for (Direction direction : Direction.values()) result[direction.ordinal() + 1] = part(level, target, direction);
        return result;
    }

    // 只跳过本次要安装的那个槽，其余六个槽必须还是原对象；这里比较对象身份，不比较它们内部配置。
    static boolean otherPartsUnchanged(Object[] before, Object[] after, Direction changedSide) {
        int changed = changedSide == null ? 0 : changedSide.ordinal() + 1;
        for (int i = 0; i < 7; i++) if (i != changed && before[i] != after[i]) return false;
        return true;
    }

    private static IllegalArgumentException unsupported(String detail) {
        return new IllegalArgumentException("machine_installation_unsupported: " + detail);
    }
}
