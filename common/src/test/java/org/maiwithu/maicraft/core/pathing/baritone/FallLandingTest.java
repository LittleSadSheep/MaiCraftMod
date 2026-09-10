// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CollisionGeometry;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.BlockStateInterface;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import sun.misc.Unsafe;

/**
 * 检查实际支撑高度、已有摔落距离、半砖与未知地形怎样影响落地判断；再确认到达格子仍需要触地或入水。
 */
public final class FallLandingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        Unsafe memory = (Unsafe) memoryField.get(null);
        var scene = new LandingScene();
        var blocks = (LoadedBlocks) memory.allocateInstance(LoadedBlocks.class);
        blocks.loaded = true;
        set(BlockStateInterface.class, blocks, "access", scene);
        var context = (CalculationContext) memory.allocateInstance(CalculationContext.class);
        set(CalculationContext.class, context, "bsi", blocks);
        set(CalculationContext.class, context, "fallOrigin", new BlockPos(0, 7, 0));
        set(CalculationContext.class, context, "fallOriginY", 7.0);
        set(CalculationContext.class, context, "fallDamageBudget", budget(4, 1));
        check(!allows(context, 0, 7, scene), "initial edge must include accumulated fall distance");
        check(allows(context, 2, 7, scene), "later edges must not inherit initial accumulated distance");
        check(allows(context, 0, 6, scene), "a proven ladder catch resets the accumulated fall");
        check(context.initialFallDistance(0, 7, 0) == 1 && context.initialFallDistance(2, 7, 0) == 0,
                "ladder catch speed includes only the current source's prior fall");
        set(CalculationContext.class, context, "fallDamageBudget", budget(5, 1));
        set(CalculationContext.class, context, "fallOriginY", 7.25);
        check(!allows(context, 0, 7, scene), "fractional current height can cross the next ceil boundary");
        scene.support = Blocks.STONE_SLAB.defaultBlockState();
        check(CollisionGeometry.supportHeight(scene, new BlockPos(1, 0, 0)) == 0.5, "actual slab collision height");
        check(allows(context, 2, 7, scene), "survivable half-slab drop is allowed");
        set(CalculationContext.class, context, "fallDamageBudget", budget(4, 0));
        check(!allows(context, 2, 7, scene), "half-slab extra distance can make a landing fatal");
        scene.support = Blocks.STONE.defaultBlockState();
        check(allows(context, 2, 7, scene), "full support at the same cell remains survivable");
        check(!context.canLandWithoutDamage(2, 7, 0, 7, 1, 0, 0, scene.support),
                "survivable damage still requires automatic protection");
        check(context.canLandWithoutDamage(2, 4, 0, 4, 1, 0, 0, scene.support), "three-block full support is harmless");
        scene.support = Blocks.STONE_SLAB.defaultBlockState();
        check(!context.canLandWithoutDamage(2, 4, 0, 4, 1, 0, 0, scene.support),
                "the extra half-slab drop also requires protection");
        blocks.loaded = false;
        check(!allows(context, 2, 7, scene), "cached or unknown landing column cannot authorize a drop");
        blocks.loaded = true;
        scene.support = Blocks.AIR.defaultBlockState();
        check(!allows(context, 2, 7, scene), "air or void is not a landing");
        scene.support = Blocks.LAVA.defaultBlockState();
        check(!allows(context, 2, 7, scene), "lava is not a supported landing");
        landingCompletion(memory);
        System.out.println("FallLandingTest: passed");
    }

    private static void landingCompletion(Unsafe memory) throws Exception {
        var player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        var destination = new BetterBlockPos(1, 1, 0);
        var ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "playerFeet" -> destination;
                    case "player" -> player;
                    default -> throw new AssertionError("unexpected live query " + method.getName());
                });
        var air = Blocks.AIR.defaultBlockState();
        set(net.minecraft.world.entity.Entity.class, player, "onGround", false);
        check(!MovementFall.reachedLanding(ctx, destination, air), "feet cell above a slab is not yet contact");
        check(MovementFall.reachedLanding(ctx, destination, Blocks.WATER.defaultBlockState()),
                "water arrival must not wait for onGround in an ordinary or extended fall");
        set(net.minecraft.world.entity.Entity.class, player, "onGround", true);
        check(MovementFall.reachedLanding(ctx, destination, air), "confirmed dry landing permits the next movement");
        check(!MovementFall.reachedLanding(ctx, destination.east(), air), "ground contact at a different cell is not arrival");
    }

    private static boolean allows(CalculationContext context, int sourceX, int effectiveY, LandingScene scene) {
        return context.canSurviveFall(sourceX, 7, 0, effectiveY, 1, 0, 0, scene.support);
    }
    private static FallDamageBudget budget(float health, float accumulated) {
        return new FallDamageBudget(health, 0, 3, 1, 0, 0, 0, 0, 0.08, accumulated, false);
    }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class LoadedBlocks extends BlockStateInterface {
        boolean loaded;
        private LoadedBlocks() { super(null); }
        public boolean worldContainsLoadedChunk(int x, int z) { return loaded; }
    }
    private static final class LandingScene implements BlockGetter {
        BlockState support = Blocks.STONE.defaultBlockState();
        public BlockState getBlockState(BlockPos pos) { return pos.getY() == 0 ? support : Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getMinBuildHeight() { return -64; }
        public int getHeight() { return 384; }
    }
}
