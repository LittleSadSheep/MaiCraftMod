// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.PathCalculationResult;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CollisionGeometry;
import baritone.pathing.movement.movements.MovementSwimClimbOut;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import sun.misc.Unsafe;

/**
 * 一格式岸沿从水域可达陆地：水面节点贴着一格高岸壁时，原生 A* 必须给出
 * 「游上沿顶」的移动段并抵达岸上目标，而不是判无路（176 实机软锁死的规划面根因）。
 */
public final class SwimShoreClimbPlanningTest {
    // 水面顶层节点 y=3；岸顶方块 y=4，站立格 y=5——正是既有 TRAVERSE/ASCEND 都覆盖不了的 +2 过渡。
    private static final BetterBlockPos START = new BetterBlockPos(2, 3, 3);
    private static final BetterBlockPos GOAL = new BetterBlockPos(8, 5, 3);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        costGates();
        planSwimShoreClimb();
        System.out.println("SwimShoreClimbPlanningTest: passed");
    }

    /** 深水节点与岸上节点都不起攀，只有水面节点可行；断言不依赖完整寻路。 */
    private static void costGates() throws Exception {
        try (var w = new InteractionWorldTestHarness()) {
            CalculationContext calc = calcContext(w, terrain());
            check(MovementSwimClimbOut.cost(calc, 5, 3, 3, 6, 3) < ActionCosts.COST_INF,
                    "surface water cell beside a one-block shore must allow climbing out");
            check(MovementSwimClimbOut.cost(calc, 2, 2, 3, 3, 3) == ActionCosts.COST_INF,
                    "submerged water cell must not plan a climb-out; swim up first");
            check(MovementSwimClimbOut.cost(calc, 8, 5, 3, 7, 3) == ActionCosts.COST_INF,
                    "land cells must never plan a swim climb-out");
            check(MovementSwimClimbOut.cost(calc, 3, 3, 3, 3, 2) == ActionCosts.COST_INF,
                    "open water without a ledge offers nothing to climb onto");
        }
    }

    /** 真实 A* 从水中央游到岸顶目标，路线必须包含游上沿顶的移动段。 */
    private static void planSwimShoreClimb() throws Exception {
        try (var w = new InteractionWorldTestHarness()) {
            Terrain scene = terrain();
            CalculationContext calc = calcContext(w, scene);
            var favoring = new Favoring(null, calc);
            var search = new AStarPathFinder(START, START.x, START.y, START.z, new GoalBlock(GOAL.x, GOAL.y, GOAL.z), favoring, calc);
            var result = search.calculate(2000, 4000);
            check(result.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL,
                    "one-block shore must be reachable from open water: " + result.getType());
            IPath path = result.getPath().orElseThrow();
            check(GOAL.equals(path.getDest()), "route must end standing on the shore, got " + path.getDest());
            check(path.movements().stream().anyMatch(m -> m instanceof MovementSwimClimbOut),
                    "the route must include the swim climb-out action face");
            for (BlockPos p : path.positions()) {
                check(scene.isLoaded(p), "route stays inside loaded terrain: " + p);
            }
        }
    }

    // ClientLevel 构造器依赖联机连接；测试夹具走 Unsafe 分配绕过构造，与既有寻路回放一致。
    private static Terrain terrain() throws Exception {
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        return (Terrain) memory.allocateInstance(Terrain.class);
    }

    private static CalculationContext calcContext(InteractionWorldTestHarness w, Terrain scene) throws Exception {
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        field(Minecraft.class, "gameDirectory").set(Minecraft.getInstance(), new File("swim-shore-climb-fixture"));
        field(Minecraft.class, "gameThread").set(Minecraft.getInstance(), Thread.currentThread());
        var type = new DimensionType(OptionalLong.empty(), true, false, false, true, 1, true, false, 0, 16, 16,
                BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.withDefaultNamespace("overworld"), 0,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
        field(Level.class, "dimensionTypeRegistration").set(scene, Holder.direct(type));
        var ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "playerFeet" -> START;
                    case "player" -> w.player;
                    case "world" -> scene;
                    case "minecraft" -> Minecraft.getInstance();
                    default -> throw new AssertionError(method.getName());
                });
        var backend = (Baritone) memory.allocateInstance(Baritone.class);
        field(Baritone.class, "playerContext").set(backend, ctx);
        var blocks = (BlocksView) memory.allocateInstance(BlocksView.class);
        blocks.scene = scene;
        field(BlockStateInterface.class, "access").set(blocks, scene);
        var calc = (CalculationContext) memory.allocateInstance(CalculationContext.class);
        for (var value : List.of(new Object[]{"baritone", backend}, new Object[]{"world", scene}, new Object[]{"bsi", blocks},
                new Object[]{"allowBreakAnyway", List.of()}, new Object[]{"precomputedData", new PrecomputedData()},
                new Object[]{"maicraftPolicy", EmbeddedBaritonePolicy.snapshot()}, new Object[]{"worldBorder", new BetterWorldBorder(scene.getWorldBorder())},
                new Object[]{"fallOrigin", new BlockPos(START.getX(), START.getY(), START.getZ())},
                new Object[]{"fallDamageBudget", new FallDamageBudget(20, 0, 3, 1, 0, 0, 0, 0, .08, 0, false)}))
            field(CalculationContext.class, (String) value[0]).set(calc, value[1]);
        calc.backtrackCostFavoringCoefficient = 1;
        // Unsafe 分配不跑构造器：水中移动费用与起跳惩罚必须补上生产默认值，否则水面行走被判 0 费用。
        field(CalculationContext.class, "waterWalkSpeed").set(calc,
                baritone.api.pathing.movement.ActionCosts.WALK_ONE_IN_WATER_COST);
        calc.jumpPenalty = Baritone.settings().jumpPenalty.value;
        // 空背包快照：下降移动的落地辅助在夹具里没有物品可用。
        field(CalculationContext.class, "landingInventory").set(calc,
                new org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan.InventorySnapshot(
                        java.util.Set.of(), false, false, false));
        field(CalculationContext.class, "collisionGeometry").set(calc,
                new CollisionGeometry(scene, true, Vec3.atBottomCenterOf(START), START));
        return calc;
    }

    private static final class BlocksView extends BlockStateInterface {
        Terrain scene;
        private BlocksView() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return scene.getBlockState(new BlockPos(x, y, z)); }
        @Override public boolean isLoaded(int x, int z) { return scene.isLoaded(new BlockPos(x, 1, z)); }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return isLoaded(x, z); }
    }

    // x<=5 为深水池（水柱 y1..3，顶层节点 y3），x>=6 为岸（实心 y0..4，站立格 y5）；
    // 池子 z=0/6 一侧岸顶只在 y1，比水面低两格、不可站立，出水口只有 x=6 那面一格沿。
    private static final class Terrain extends ClientLevel {
        private Terrain() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos p) { return p.getX() >= 0 && p.getX() <= 14 && p.getZ() >= 0 && p.getZ() <= 8; }
        @Override public BlockState getBlockState(BlockPos p) {
            if (!isLoaded(p)) return Blocks.BEDROCK.defaultBlockState();
            if (p.getY() == 0) return Blocks.STONE.defaultBlockState();
            if (p.getX() <= 5) {
                if (p.getZ() < 1 || p.getZ() > 5) return p.getY() <= 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                return p.getY() <= 3 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
            return p.getY() <= 4 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public WorldBorder getWorldBorder() { return new WorldBorder(); }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getHeight() { return 16; }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
