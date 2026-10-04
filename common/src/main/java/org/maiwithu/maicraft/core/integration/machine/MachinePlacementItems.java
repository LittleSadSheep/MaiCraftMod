// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.HashSet;
import java.util.function.Predicate;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.maiwithu.maicraft.core.mixin.BlockItemPlacementAccess;
import org.maiwithu.maicraft.core.integration.create.CreateFunnelPlacement;

/** 使用依状态而定的原生物品，并审核放置后的状态；不伪造物品数据，也不直接写入世界。 */
public final class MachinePlacementItems {
    private static final String ROTATE = "com.simibubi.create.content.kinetics.base.IRotate";
    private MachinePlacementItems() {}

    /** 先完成承载面，再装依附其上的运输部件；只调整施工顺序，不改作者的部件位置。 */
    public static List<BlockPos> supportDependencies(BlockState state) {
        // 墙上火把先等背后的实心支座完成；它使用普通火把物品，不能因此遗漏真正的承载方向。
        if(state.getBlock() instanceof WallTorchBlock || state.getBlock() instanceof RedstoneWallTorchBlock)
            return List.of(BlockPos.ZERO.relative(state.getValue(WallTorchBlock.FACING).getOpposite()));
        return beltTunnel(state) ? List.of(BlockPos.ZERO.below()) : CreateFunnelPlacement.dependencies(state);
    }

    private static boolean beltTunnel(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.equals("create:andesite_tunnel") || id.equals("create:brass_tunnel");
    }
    public static String itemId(String blockId, Map<String, String> properties) {
        return blockId.equals("create:gearbox") && Set.of("x", "z").contains(properties.getOrDefault("axis", "y"))
                ? "create:vertical_gearbox" : blockId;
    }
    public static Item itemFor(BlockState state) {
        if (state.isAir()) return Items.AIR;
        // 源流体由它自己的原生桶物品安装，不把液体方块的 AIR 物品误当成材料，也不借生物桶生成额外实体。
        if (state.getBlock() instanceof LiquidBlock) return fluidBucket(state);
        BlockItem nativeVariant = CreateFunnelPlacement.material(state);
        if (nativeVariant != null) return nativeVariant;
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String, String> properties = state.hasProperty(BlockStateProperties.AXIS)
                ? Map.of("axis", state.getValue(BlockStateProperties.AXIS).getName()) : Map.of();
        String itemId = itemId(blockId, properties);
        Item selected = itemId.equals(blockId) ? state.getBlock().asItem() : BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        // 站立与墙挂形态由同一个原生物品注册，asItem 已给出对应关系；不能只比较物品的主方块而拒绝墙火把。
        boolean paired=selected instanceof StandingAndWallBlockItem && selected==state.getBlock().asItem();
        if (!(selected instanceof BlockItem item) || item.getBlock() != state.getBlock() && !paired)
            throw new IllegalArgumentException("machine_native_placement_item_unavailable: " + blockId + " " + properties);
        return selected;
    }

    public static BucketItem fluidBucket(BlockState state) {
        var fluid = state.getFluidState();
        if (!(state.getBlock() instanceof LiquidBlock) || fluid.isEmpty() || !fluid.isSource()
                || !fluid.createLegacyBlock().equals(state))
            throw new IllegalArgumentException("machine_fluid_requires_native_source_state");
        Item item = fluid.getType().getBucket();
        if (!(item instanceof BucketItem bucket) || item instanceof MobBucketItem || item == Items.BUCKET)
            throw new IllegalArgumentException("machine_source_fluid_bucket_unavailable");
        return bucket;
    }
    public static BlockState placementState(BlockItem item, BlockPlaceContext context, boolean projectedSupport) {
        // 真实支承处统一调用物品原生分派，墙火把、带式漏斗等才能选择实际形态；虚拟支承仍保持方块预测。
        if (!projectedSupport && item instanceof BlockItemPlacementAccess access)
            return access.maicraft$placementState(context);
        return item.getBlock().getStateForPlacement(context);
    }
    /** 复现 VerticalGearboxItem.updateCustomBlockEntityTag 的规则；普通 GearboxBlock 放置始终保持 Y 轴。 */
    public static BlockState projectedFinalState(ItemStack stack, Level level, BlockPos target, Direction candidateHorizontal, BlockState initial) {
        if (initial == null || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("create:vertical_gearbox")) return initial;
        if (!BuiltInRegistries.BLOCK.getKey(initial.getBlock()).toString().equals("create:gearbox") || !initial.hasProperty(BlockStateProperties.AXIS)) return null;
        Set<Direction.Axis> neighbors = neighboringShaftAxes(level,target);
        return neighbors == null ? null : initial.setValue(BlockStateProperties.AXIS, verticalGearboxAxis(neighbors, candidateHorizontal));
    }
    private static Set<Direction.Axis> neighboringShaftAxes(Level level, BlockPos target) {
        Set<Direction.Axis> neighbors = new HashSet<>();
        for (Direction face : Direction.Plane.HORIZONTAL) {
            BlockPos at = target.relative(face); if (!level.isLoaded(at)) return null;
            BlockState state = level.getBlockState(at);
            if (NativeApi.is(state.getBlock(), ROTATE) && NativeApi.truth(NativeApi.call(state.getBlock(), ROTATE,
                    "hasShaftTowards", level, at, state, face.getOpposite()))) neighbors.add(face.getAxis());
        }
        return neighbors;
    }
    // 穷尽站位仍放不出目标状态时，报告原生物品强制朝向的现场原因，不把此类设计冲突继续说成无路可走。
    public static Map<String,Object> placementConflict(Item item, Level level, BlockPos target, BlockState requested,
            Predicate<BlockState> acceptsPlacedState) {
        if (beltTunnel(requested)) {
            // 普通站位均已尝试后，再给出隧道原生落点判定和实际承载面；不能把承载条件不成立一直说成导航失败。
            try {
                BlockPos below = target.below();
                if (!level.isLoaded(below) || NativeApi.truth(NativeApi.call(requested.getBlock(), null,
                        "isValidPositionForPlacement", requested, level, target))) return Map.of();
                BlockState support = level.getBlockState(below);
                return Map.of("rule", "create_belt_tunnel_native_support",
                        "support_position", List.of(below.getX(), below.getY(), below.getZ()),
                        "actual_support_block", BuiltInRegistries.BLOCK.getKey(support.getBlock()).toString(),
                        "actual_support_state", support.toString(),
                        "native_position_valid", false,
                        "detail", "Native BeltTunnelBlock.isValidPositionForPlacement returned false: "
                                + "the cell below is not a horizontal Create belt; observed " + support);
            } catch (RuntimeException | LinkageError unavailable) {
                // 原生接口读取失败不猜放置条件，保留原执行器的实际站位与交互结果。
                return Map.of();
            }
        }
        if (!BuiltInRegistries.ITEM.getKey(item).toString().equals("create:vertical_gearbox")
                || !BuiltInRegistries.BLOCK.getKey(requested.getBlock()).toString().equals("create:gearbox")
                || !requested.hasProperty(BlockStateProperties.AXIS)) return Map.of();
        try {
            Set<Direction.Axis> neighbors = neighboringShaftAxes(level,target);
            if (neighbors == null || neighbors.size() != 1) return Map.of();
            // 图纸允许任意朝向时沿用原匹配规则，不能只因默认展示状态不同就伪报冲突。
            if (acceptsPlacedState.test(requested.setValue(BlockStateProperties.AXIS,verticalGearboxAxis(neighbors,Direction.NORTH)))) return Map.of();
            return verticalGearboxConflict(neighbors,requested.getValue(BlockStateProperties.AXIS));
        } catch (RuntimeException | LinkageError unavailable) { return Map.of(); }
    }
    static Map<String,Object> verticalGearboxConflict(Set<Direction.Axis> neighbors, Direction.Axis requested) {
        if (neighbors.size() != 1) return Map.of();
        Direction.Axis forced = verticalGearboxAxis(neighbors,Direction.NORTH);
        if (requested == forced) return Map.of();
        return Map.of("rule","create_vertical_gearbox_neighbor_alignment",
                "requested_properties",Map.of("axis",requested.getName()),
                "native_generated_properties",Map.of("axis",forced.getName()),
                "neighboring_shaft_axes",neighbors.stream().map(Direction.Axis::getName).sorted().toList(),
                "detail","Native vertical gearbox placement forces axis=" + forced.getName()
                        + " from the current horizontal shaft neighbors; requested axis=" + requested.getName()
                        + ". Revise the declared axis or neighboring shaft layout before resubmitting this cell.");
    }
    public static Direction.Axis verticalGearboxAxis(Set<Direction.Axis> neighboringShaftAxes, Direction candidateHorizontal) {
        if (candidateHorizontal == null || candidateHorizontal.getAxis() == Direction.Axis.Y)
            throw new IllegalArgumentException("vertical_gearbox_horizontal_view_required");
        if (neighboringShaftAxes.size() == 1) {
            Direction.Axis axis = neighboringShaftAxes.iterator().next();
            if (axis == Direction.Axis.X) return Direction.Axis.Z;
            if (axis == Direction.Axis.Z) return Direction.Axis.X;
        }
        return candidateHorizontal.getClockWise().getAxis();
    }
}
