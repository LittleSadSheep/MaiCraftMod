package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import sun.misc.Unsafe;

/** Both sides of the real diagonal cost calculation must reject dangerous corner support. */
public final class DiagonalHazardTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var memory = (Unsafe) field.get(null);
        var client = (net.minecraft.client.Minecraft) memory.allocateInstance(net.minecraft.client.Minecraft.class);
        var directory = net.minecraft.client.Minecraft.class.getDeclaredField("gameDirectory"); directory.setAccessible(true);
        directory.set(client, new java.io.File("diagonal-settings-fixture"));
        var thread = net.minecraft.client.Minecraft.class.getDeclaredField("gameThread"); thread.setAccessible(true);
        thread.set(client, Thread.currentThread());
        var global = net.minecraft.client.Minecraft.class.getDeclaredField("instance"); global.setAccessible(true);
        Object previous = global.get(null); global.set(null, client);
        try {
        var context = (Scene) memory.allocateInstance(Scene.class);
        var blocks = (baritone.utils.BlockStateInterface) memory.allocateInstance(baritone.utils.BlockStateInterface.class);
        var access = baritone.utils.BlockStateInterface.class.getDeclaredField("access"); access.setAccessible(true);
        access.set(blocks, net.minecraft.world.level.EmptyBlockGetter.INSTANCE);
        var bsi = CalculationContext.class.getDeclaredField("bsi"); bsi.setAccessible(true); bsi.set(context, blocks);
        var cache = CalculationContext.class.getDeclaredField("precomputedData"); cache.setAccessible(true);
        cache.set(context, new PrecomputedData());
        for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            context.hazard = null;
            check(cost(context, x, z) < ActionCosts.COST_INF, "ordinary diagonal floor remains walkable");
            for (BlockPos corner : new BlockPos[]{new BlockPos(0, -1, z), new BlockPos(x, -1, 0)}) {
                context.hazard = corner;
                check(cost(context, x, z) >= ActionCosts.COST_INF, "both swept corners must reject magma: " + corner);
            }
        }
        System.out.println("DiagonalHazardTest: passed");
        } finally { global.set(null, previous); }
    }

    private static double cost(Scene context, int x, int z) {
        var result = new MutableMoveResult(); result.reset();
        MovementDiagonal.cost(context, 0, 0, 0, x, z, result);
        return result.cost;
    }

    private static final class Scene extends CalculationContext {
        BlockPos hazard;
        private Scene() { super(null); } // Allocated without a live Minecraft client above.
        @Override public BlockState get(int x, int y, int z) {
            return (new BlockPos(x, y, z).equals(hazard) ? Blocks.MAGMA_BLOCK
                    : y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
