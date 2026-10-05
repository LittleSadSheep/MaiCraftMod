package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDownward;
import baritone.pathing.movement.movements.MovementPillar;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import sun.misc.Unsafe;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * 寻路不得把锄好的耕地踩回泥土:行走穿过耕地要付额外代价让路径默认绕行,
 * 而跳跃/跌落把落点放在耕地上的移动(上跳、斜跳、柱跳、下落)直接禁行。
 */
public final class FarmlandProtectionTest {
    private static Unsafe memory;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        memory = (Unsafe) field.get(null);
        var client = (net.minecraft.client.Minecraft) memory.allocateInstance(net.minecraft.client.Minecraft.class);
        var directory = net.minecraft.client.Minecraft.class.getDeclaredField("gameDirectory"); directory.setAccessible(true);
        directory.set(client, new java.io.File("farmland-settings-fixture"));
        var thread = net.minecraft.client.Minecraft.class.getDeclaredField("gameThread"); thread.setAccessible(true);
        thread.set(client, Thread.currentThread());
        var global = net.minecraft.client.Minecraft.class.getDeclaredField("instance"); global.setAccessible(true);
        Object previous = global.get(null); global.set(null, client);
        try {
            traverse();
            diagonal();
            ascend();
            descend();
            pillar();
            downward();
            System.out.println("FarmlandProtectionTest: passed");
        } finally { global.set(null, previous); }
    }

    private static void traverse() throws Exception {
        var plain = scene(BlockPos.ZERO);
        var farmland = scene(new BlockPos(1, -1, 1));
        double base = MovementTraverse.cost(plain, 0, 0, 0, 1, 1);
        double overFarm = MovementTraverse.cost(farmland, 0, 0, 0, 1, 1);
        check(base < ActionCosts.COST_INF, "ordinary traverse stays walkable");
        check(overFarm > base, "walking onto farmland must cost more than ordinary ground");
    }

    private static void diagonal() throws Exception {
        var plain = scene(BlockPos.ZERO);
        var farmland = scene(new BlockPos(1, -1, 1));
        double base = costOf(new MutableMoveResult(), plain);
        double overFarm = costOf(new MutableMoveResult(), farmland);
        check(base < ActionCosts.COST_INF, "ordinary diagonal stays walkable");
        check(overFarm > base, "diagonal across farmland must cost more than ordinary ground");

        // 斜向跳上耕地:落点禁行
        var jump = scene(new BlockPos(1, 0, 1));
        var jumpStone = scene(new BlockPos(1, 0, 1));
        allowFlag(jump, "allowDiagonalAscend");
        allowFlag(jumpStone, "allowDiagonalAscend");
        jumpStone.set(1, 0, 1, Blocks.STONE);
        check(costOf(new MutableMoveResult(), jumpStone) < ActionCosts.COST_INF, "diagonal ascend onto stone stays allowed");
        check(costOf(new MutableMoveResult(), jump) >= ActionCosts.COST_INF, "diagonal ascend onto farmland must be forbidden");
    }

    private static double costOf(MutableMoveResult result, Scene context) {
        result.reset();
        MovementDiagonal.cost(context, 0, 0, 0, 1, 1, result);
        return result.cost;
    }

    private static void ascend() throws Exception {
        var stone = scene(BlockPos.ZERO);
        stone.set(1, 0, 1, Blocks.STONE);
        var farmland = scene(BlockPos.ZERO);
        farmland.set(1, 0, 1, Blocks.FARMLAND);
        double ontoStone = MovementAscend.cost(stone, 0, 0, 0, 1, 1);
        double ontoFarm = MovementAscend.cost(farmland, 0, 0, 0, 1, 1);
        check(ontoStone < ActionCosts.COST_INF, "ascending onto stone stays allowed");
        check(ontoFarm >= ActionCosts.COST_INF, "jumping onto farmland must be forbidden");
    }

    private static void descend() throws Exception {
        var stone = scene(BlockPos.ZERO);
        stone.set(1, -1, 1, Blocks.AIR); // 前方一列留空,只考察落点支撑
        stone.overrideCanLandWithoutDamage = true;
        var farmland = scene(BlockPos.ZERO);
        farmland.set(1, -1, 1, Blocks.AIR);
        farmland.set(1, -2, 1, Blocks.FARMLAND);
        farmland.overrideCanLandWithoutDamage = true;
        var stoneResult = new MutableMoveResult();
        MovementDescend.cost(stone, 0, 0, 0, 1, 1, stoneResult);
        check(stoneResult.cost < ActionCosts.COST_INF, "one-block descent onto stone stays allowed");
        var farmResult = new MutableMoveResult();
        MovementDescend.cost(farmland, 0, 0, 0, 1, 1, farmResult);
        check(farmResult.cost >= ActionCosts.COST_INF, "descending onto farmland must be forbidden");
    }

    private static void pillar() throws Exception {
        var plain = scene(BlockPos.ZERO);
        var farmland = scene(new BlockPos(0, -1, 0)); // 脚下支撑格
        check(MovementPillar.cost(plain, 0, 0, 0) < ActionCosts.COST_INF, "pillaring from ordinary ground stays allowed");
        check(MovementPillar.cost(farmland, 0, 0, 0) >= ActionCosts.COST_INF, "pillaring (jumping in place) from farmland must be forbidden");
    }

    private static void downward() throws Exception {
        var plain = scene(BlockPos.ZERO);
        var farmland = scene(BlockPos.ZERO);
        plain.set(0, -1, 0, Blocks.AIR); // 身前一格留空为洞口
        farmland.set(0, -1, 0, Blocks.AIR);
        farmland.set(0, -2, 0, Blocks.FARMLAND);
        // Unsafe 分配跳过构造器,allowDownward 需在场景里显式打开
        allowFlag(plain, "allowDownward");
        allowFlag(farmland, "allowDownward");
        check(MovementDownward.cost(plain, 0, 0, 0) < ActionCosts.COST_INF, "dropping into a hole onto stone stays allowed");
        check(MovementDownward.cost(farmland, 0, 0, 0) >= ActionCosts.COST_INF, "dropping onto farmland must be forbidden");
    }

    /** Unsafe 分配跳过构造器,final 布尔开关需按字段名经 Unsafe 写入测试场景。 */
    private static void allowFlag(Scene scene, String fieldName) throws Exception {
        var flag = CalculationContext.class.getDeclaredField(fieldName); flag.setAccessible(true);
        memory.putBoolean(scene, memory.objectFieldOffset(flag), true);
    }

    private static Scene scene(BlockPos farmland) throws Exception {
        var scene = (Scene) memory.allocateInstance(Scene.class);
        scene.overrides = new Long2ObjectOpenHashMap<>();
        var cache = CalculationContext.class.getDeclaredField("precomputedData"); cache.setAccessible(true);
        cache.set(scene, new PrecomputedData());
        var blocks = (baritone.utils.BlockStateInterface) memory.allocateInstance(baritone.utils.BlockStateInterface.class);
        var access = baritone.utils.BlockStateInterface.class.getDeclaredField("access"); access.setAccessible(true);
        access.set(blocks, net.minecraft.world.level.EmptyBlockGetter.INSTANCE);
        var bsi = CalculationContext.class.getDeclaredField("bsi"); bsi.setAccessible(true);
        bsi.set(scene, blocks);
        scene.set(farmland.getX(), farmland.getY(), farmland.getZ(), Blocks.FARMLAND);
        return scene;
    }

    private static final class Scene extends CalculationContext {
        private Long2ObjectMap<Block> overrides;
        private boolean overrideCanLandWithoutDamage;
        // Allocated without a live Minecraft client above; no constructor runs.

        private Scene() { super(null); }

        void set(int x, int y, int z, Block block) { overrides.put(BlockPos.asLong(x, y, z), block); }

        @Override public BlockState get(int x, int y, int z) {
            Block override = overrides.get(BlockPos.asLong(x, y, z));
            if (override != null) return override.defaultBlockState();
            Block floor = y <= -1 ? Blocks.STONE : Blocks.AIR;
            return floor.defaultBlockState();
        }

        @Override public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return 0; // 场景不模拟垫块放置条件,柱跳代价只考察起跳支撑
        }

        @Override public boolean canLandWithoutDamage(int x, int y, int z, int effectiveStartHeight,
                                                      int destX, int supportY, int destZ, BlockState support) {
            return overrideCanLandWithoutDamage;
        }
    }

    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
