// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.mixin.BlockItemPlacementAccess;
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import java.util.List;
import sun.misc.Unsafe;

public final class MachinePlacementItemsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        check(MachinePlacementItems.itemId("create:gearbox", Map.of()).equals("create:gearbox"), "default gearbox uses its normal native item");
        check(MachinePlacementItems.itemId("create:gearbox", Map.of("axis", "y")).equals("create:gearbox"), "Y-axis gearbox needs the normal item");
        for (String axis : new String[] {"x", "z"}) check(MachinePlacementItems.itemId("create:gearbox", Map.of("axis", axis)).equals("create:vertical_gearbox"),
                "horizontal gearbox axis needs the vertical native item for both materials and construction");
        check(MachinePlacementItems.itemId("create:shaft", Map.of("axis", "x")).equals("create:shaft"), "unrelated axis blocks retain their actual item");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(Direction.Axis.X), Direction.NORTH) == Direction.Axis.Z, "a native X-axis shaft neighbor selects Z regardless of candidate view");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(Direction.Axis.Z), Direction.EAST) == Direction.Axis.X, "a native Z-axis shaft neighbor selects X regardless of candidate view");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(), Direction.NORTH) == Direction.Axis.X, "no neighbor uses clockwise candidate horizontal view");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(), Direction.EAST) == Direction.Axis.Z, "candidate yaw can naturally select the other horizontal axis");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(Direction.Axis.X, Direction.Axis.Z), Direction.EAST) == Direction.Axis.Z,
                "conflicting native neighbor axes use the original item's view fallback");
        // 复现压机后方已有Z向链箱、蓝图仍要求齿轮箱axis=z的情形：实际物品会强制生成axis=x。
        var conflict = MachinePlacementItems.verticalGearboxConflict(Set.of(Direction.Axis.Z),Direction.Axis.Z);
        check(((Map<?,?>)conflict.get("native_generated_properties")).get("axis").equals("x")
                && ((Map<?,?>)conflict.get("requested_properties")).get("axis").equals("z"),"state conflict exposes both requested and native axes");
        check(MachinePlacementItems.verticalGearboxConflict(Set.of(Direction.Axis.Z),Direction.Axis.X).isEmpty()
                && MachinePlacementItems.verticalGearboxConflict(Set.of(),Direction.Axis.Z).isEmpty()
                && MachinePlacementItems.verticalGearboxConflict(Set.of(Direction.Axis.X,Direction.Axis.Z),Direction.Axis.Z).isEmpty(),
                "a valid or freely view-selectable native state is not reported as a forced conflict");
        // 固定翼左轮反向传动使用墙上红石火把；材料、承载方向和持久化都必须保留同一原生形态。
        for(var pair:List.of(Map.entry(Blocks.WALL_TORCH,Items.TORCH),Map.entry(Blocks.REDSTONE_WALL_TORCH,Items.REDSTONE_TORCH),
                Map.entry(Blocks.SOUL_WALL_TORCH,Items.SOUL_TORCH)))
            check(MachinePlacementItems.itemFor(pair.getKey().defaultBlockState())==pair.getValue(),"wall variant lost its native item");
        for(var facing:Direction.Plane.HORIZONTAL) {
            var state=Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,facing);
            check(MachinePlacementItems.supportDependencies(state).equals(List.of(BlockPos.ZERO.relative(facing.getOpposite()))),"wall support points away from the mount");
            var target=new BuildTaskRecord.Target(state,Items.REDSTONE_TORCH,BlockPos.ZERO,"墙上红石火把",null,null,null);
            var restored=BuildProjectTargets.decode(BuildProjectTargets.encode(List.of(target))).getFirst();
            check(restored.item()==Items.REDSTONE_TORCH&&restored.desiredState().equals(state),"wall item and facing did not survive project persistence");
        }
        // 测试 JVM 无需加载 Create 注册器；用原版六向状态承载同名原生 FACING，核对链路的施工依赖。
        for(Direction facing:Direction.values()) {
            var state=Blocks.END_ROD.defaultBlockState().setValue(BlockStateProperties.FACING,facing);
            var required=MachinePlacementItems.supportDependencies("create:redstone_link",state);
            check(required.equals(List.of(BlockPos.ZERO.relative(facing.getOpposite()))),"redstone link support must precede its native mounting face");
        }
        var downLink=Blocks.END_ROD.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.DOWN);
        var receiver=new BlockPos(0,1,0);var shaft=new BlockPos(0,2,0);
        var dependencies=MachinePlacementItems.supportDependencies("create:redstone_link",downLink).stream().map(receiver::offset).toList();
        check(dependencies.equals(List.of(shaft))&&MachinePlacementDependencies.layers(Map.of(receiver,dependencies)).equals(List.of(List.of(receiver))),
                "a receiver below its shaft must be scheduled as an attachment after the shaft, not as the first lower construction layer");
        // 普通测试 JVM 没有 Mixin；此适配器只复现原生分派入口，证明预测不再绕过物品自己的形态选择。
        var unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        var paired=(PairedItem)((Unsafe)unsafeField.get(null)).allocateInstance(PairedItem.class);
        check(MachinePlacementItems.placementState(paired,null,false).is(Blocks.WALL_TORCH)&&paired.called,"native item placement was bypassed");
        System.out.println("MachinePlacementItemsTest: native gearbox and wall-item placement passed");
    }
    private static final class PairedItem extends StandingAndWallBlockItem implements BlockItemPlacementAccess {
        boolean called;
        private PairedItem(){super(Blocks.TORCH,Blocks.WALL_TORCH,new Item.Properties(),Direction.DOWN);}
        public BlockState maicraft$placementState(BlockPlaceContext context){called=true;return Blocks.WALL_TORCH.defaultBlockState();}
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
