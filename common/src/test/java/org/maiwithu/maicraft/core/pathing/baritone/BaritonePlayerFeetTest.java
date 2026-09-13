// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.utils.player.BaritonePlayerContext;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import sun.misc.Unsafe;

/** 用真实楼梯／半砖碰撞盒核对脚位约定；只构造只读身体观察，不启动游戏或调用移动输入。 */
public final class BaritonePlayerFeetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var client = (Minecraft) memory.allocateInstance(Minecraft.class);
        var player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        var level = (FeetLevel) memory.allocateInstance(FeetLevel.class); level.blocks = new HashMap<>(); level.loaded = true;
        client.player = player; client.level = level;
        field(Entity.class, "level").set(player, level); field(LocalPlayer.class, "clientLevel").set(player, level);
        var context = new BaritonePlayerContext(null, client);
        BlockPos support = new BlockPos(-3, 84, -6);
        Vec3 lowerStep = new Vec3(-2.875148, 84.5, -5.505909);
        level.blocks.put(support, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        setPosition(player, lowerStep); realFloor(level, lowerStep);
        check(context.playerFeet().equals(support.above()) && context.playerFeet().equals(PlayerNav.playerFeet(player)),
                "the actual lower stair surface at Y84.5 must be node Y85 for both Baritone and construction");
        check(player.position().equals(lowerStep), "normalizing a graph node must never move the physical player");

        level.blocks.put(support, Blocks.STONE_SLAB.defaultBlockState());
        Vec3 lowerSlab = new Vec3(-2.5, 84.5, -5.5); setPosition(player, lowerSlab); realFloor(level, lowerSlab);
        check(context.playerFeet().equals(support.above()) && context.playerFeet().equals(PlayerNav.playerFeet(player)),
                "the existing bottom-slab convention must remain unchanged");

        level.blocks.put(support, Blocks.STONE.defaultBlockState());
        Vec3 fullFloor = new Vec3(-2.5, 85, -5.5); setPosition(player, fullFloor); realFloor(level, fullFloor);
        check(context.playerFeet().equals(support.above()) && context.playerFeet().equals(PlayerNav.playerFeet(player)),
                "ordinary full-block ground still reports its real feet cell");

        level.blocks.clear(); setPosition(player, lowerStep);
        check(context.playerFeet().equals(support), "known air at a fractional height is not automatically rounded upward");
        level.blocks.put(support, Blocks.DARK_OAK_STAIRS.defaultBlockState()); level.loaded = false; int reads = level.reads;
        check(context.playerFeet().equals(support) && level.reads == reads,
                "an unloaded cell cannot be queried or promoted based on an unobserved staircase");
        client.level = null;
        check(context.playerFeet().equals(support), "a missing world preserves raw coordinates without inventing footing");
        System.out.println("BaritonePlayerFeetTest: native lower-stair/slab geometry, ordinary ground and unknown cells passed");
    }

    private static void realFloor(FeetLevel level, Vec3 feet) {
        AABB body = new AABB(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + 1.8, feet.z + .3).deflate(1e-6);
        boolean supported = false;
        for (var entry : level.blocks.entrySet()) for (AABB local : entry.getValue().getCollisionShape(level, entry.getKey()).toAabbs()) {
            AABB box = local.move(entry.getKey());
            check(!box.intersects(body), "the test's lower-half player body must fit the native collision shape");
            supported |= Math.abs(box.maxY - feet.y) < 1e-6 && box.maxX > body.minX && box.minX < body.maxX
                    && box.maxZ > body.minZ && box.minZ < body.maxZ;
        }
        check(supported, "the test feet height must come from an actual native support surface");
    }
    private static void setPosition(LocalPlayer player, Vec3 position) throws Exception {
        // 只设置回归夹具的输入样本；被测玩家入口仅允许读取，不能改坐标或物理速度。
        field(Entity.class, "position").set(player, position);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { /* 原生字段可能定义在父类。 */ }
        }
        throw new NoSuchFieldException(name);
    }
    private static final class FeetLevel extends ClientLevel {
        Map<BlockPos, BlockState> blocks; boolean loaded; int reads;
        private FeetLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos pos) { return loaded; }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (!loaded) throw new AssertionError("unloaded feet observation attempted a world read");
            reads++; return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
