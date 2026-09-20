// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import sun.misc.Unsafe;

/** 通过实际施工预测入口调用直接读取玩家角度的方块；夹具桥接 getter，不冒充生产 Mixin 或 Mek 实机验收。 */
public final class BuildPlacementPlayerRotationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(6.5, 1, 8.5));
            var player = copyPlayer(world.player); player.setYRot(0); player.setXRot(0);
            var block = fixtureRegistry(BuiltInRegistries.BLOCK,
                    () -> new PlayerFacingBlock(BlockBehaviour.Properties.of().dynamicShape()));
            var item = fixtureRegistry(BuiltInRegistries.ITEM, () -> new BlockItem(block, new Item.Properties()));
            ItemStack stack = new ItemStack(item);
            BlockPos support = new BlockPos(8, 1, 8); world.set(support, Blocks.STONE.defaultBlockState());
            var hit = new BlockHitResult(support.getCenter().add(0, .5, 0), Direction.UP, support, false);
            Vec3 beforePosition = player.position(); Object beforeInput = player.input;

            // 仅覆盖放置上下文的朝向仍会被直接读玩家的方块忽略，这就是此前排除可行机器站位的缺口。
            var context = new BlockPlaceContext(new UseOnContext(world.level, player, InteractionHand.MAIN_HAND, stack, hit) {}) {
                @Override public Direction getHorizontalDirection() { return Direction.NORTH; }
                @Override public float getRotation() { return 180; }
            };
            check(block.getStateForPlacement(context).getValue(BlockStateProperties.FACING) == Direction.NORTH,
                    "真实玩家仍朝0度，单改context不能得到候选南向结果");

            // 固定真实镜头，分别模拟水平与上下放置；每次原生计算都必须读取本次候选，而不是上一候选。
            checkFacing(player, stack, hit, 180, 0, Direction.SOUTH);
            checkFacing(player, stack, hit, 90, 0, Direction.EAST);
            checkFacing(player, stack, hit, 0, 70, Direction.UP);
            checkFacing(player, stack, hit, 0, -70, Direction.DOWN);
            block.reject = true;
            check(predict(player, stack, hit, 180, 70) == null, "原生计算拒绝仍沿用未知结果，不伪造可放状态");
            check(player.getYRot() == 0 && player.getXRot() == 0 && player.position().equals(beforePosition)
                            && player.input == beforeInput && !player.input.shiftKeyDown,
                    "正常或异常预测都不得转动真实镜头、移动角色或按下潜行");
            check(world.blockUses() == 0 && world.itemUses() == 0 && stack.getCount() == 1
                            && world.level.getBlockState(support).is(Blocks.STONE)
                            && world.level.getBlockState(support.above()).isAir(),
                    "只算放法，不能发点击、花材料或改变支撑与目标方块");
        }
        System.out.println("BuildPlacementPlayerRotationTest: candidate yaw/pitch and unchanged real player passed; fixture getter bridge only");
    }

    private static void checkFacing(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                    float yaw, float pitch, Direction expected) throws Exception {
        BlockState predicted = predict(player, stack, hit, yaw, pitch);
        check(predicted != null && predicted.getValue(BlockStateProperties.FACING) == expected,
                "直接读玩家角度的方块必须得到候选朝向 " + expected);
        check(player.getYRot() == 0 && player.getXRot() == 0, "候选计算结束必须读回真实镜头");
    }

    private static BlockState predict(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                      float yaw, float pitch) throws Exception {
        var method = BuildPlacementGeometry.class.getDeclaredMethod("predictedState", LocalPlayer.class, ItemStack.class,
                BlockHitResult.class, float.class, float.class, boolean.class, BlockPos.class);
        method.setAccessible(true);
        return (BlockState) method.invoke(null, player, stack, hit, yaw, pitch, false, hit.getBlockPos().above());
    }

    // 方块故意绕过context角度读取玩家，复现模组可合法采用的原生调用方式，不给生产代码添加物品特判。
    private static final class PlayerFacingBlock extends Block {
        boolean reject;
        PlayerFacingBlock(BlockBehaviour.Properties properties) { super(properties); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.FACING);
        }
        @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
            if (reject) throw new IllegalStateException("fixture native placement refused");
            var player = context.getPlayer(); float pitch = player.getXRot();
            Direction direction = pitch >= 65 ? Direction.UP : pitch <= -65 ? Direction.DOWN
                    : Direction.fromYRot(player.getYRot()).getOpposite();
            return defaultBlockState().setValue(BlockStateProperties.FACING, direction);
        }
    }

    // 单元环境没有加载Mixin，只在惰性测试玩家中桥接相同getter；真正的注入由后续实机固定朝向施工核验。
    private static final class FixturePlayer extends LocalPlayer {
        private FixturePlayer() { super(null, null, null, null, null, false, false); }
        @Override public float getYRot() { return PlacementPlayerProjection.projectYaw(this, super.getYRot()); }
        @Override public float getXRot() { return PlacementPlayerProjection.projectPitch(this, super.getXRot()); }
    }
    private static FixturePlayer copyPlayer(LocalPlayer original) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        FixturePlayer player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        for (Class<?> owner = LocalPlayer.class; owner != Object.class; owner = owner.getSuperclass()) {
            for (Field member : owner.getDeclaredFields()) if (!Modifier.isStatic(member.getModifiers())) {
                member.setAccessible(true); member.set(player, member.get(original));
            }
        }
        return player;
    }
    // 夹具物品仅存在于独立测试进程；注册表的冻结标记和待注册持有者无论成功失败都原样恢复。
    private static <T> T fixtureRegistry(Object registry, Supplier<T> create) throws Exception {
        Field holders = field(MappedRegistry.class, "unregisteredIntrusiveHolders"), frozen = field(MappedRegistry.class, "frozen");
        Object previous = holders.get(registry); boolean wasFrozen = frozen.getBoolean(registry);
        try { holders.set(registry, new IdentityHashMap<>()); frozen.setBoolean(registry, false); return create.get(); }
        finally { holders.set(registry, previous); frozen.setBoolean(registry, wasFrozen); }
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        return BuildPlacementSneakCreateHarness.field(owner, name);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
