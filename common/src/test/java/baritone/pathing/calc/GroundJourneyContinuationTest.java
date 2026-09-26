package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.PathCalculationResult;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CollisionGeometry;
import baritone.pathing.path.PathExecutor;
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
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goals.GoalAvoidEntities;
import sun.misc.Unsafe;

/** 空背包沿唯一远端目标走路；原生 A* 绕危险地形、返回部分路径，再由原生调度同刻接续。 */
public final class GroundJourneyContinuationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            // 设置只供原生设置加载使用的目录与线程，测试不会启动游戏或向存档写入方块。
            field(Minecraft.class, "gameDirectory").set(Minecraft.getInstance(), new File("ground-journey-fixture"));
            field(Minecraft.class, "gameThread").set(Minecraft.getInstance(), Thread.currentThread());
            var scene = (Terrain) memory.allocateInstance(Terrain.class); scene.loadedThrough = 15;
            var type = new DimensionType(OptionalLong.empty(), true, false, false, true, 1, true, false, 0, 16, 16,
                    BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.withDefaultNamespace("overworld"), 0,
                    new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
            field(Level.class, "dimensionTypeRegistration").set(scene, Holder.direct(type));
            var ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "playerFeet" -> scene.feet;
                        case "player" -> w.player;
                        case "world" -> scene;
                        case "minecraft" -> Minecraft.getInstance();
                        default -> throw new AssertionError(method.getName());
                    });
            var backend = (Baritone) memory.allocateInstance(Baritone.class); field(Baritone.class, "playerContext").set(backend, ctx);
            var blocks = (BlocksView) memory.allocateInstance(BlocksView.class); blocks.scene = scene;
            field(BlockStateInterface.class, "access").set(blocks, scene);
            var calc = (CalculationContext) memory.allocateInstance(CalculationContext.class);
            for (var value : List.of(new Object[]{"baritone", backend}, new Object[]{"world", scene}, new Object[]{"bsi", blocks},
                    new Object[]{"allowBreakAnyway", List.of()}, new Object[]{"precomputedData", new PrecomputedData()},
                    new Object[]{"maicraftPolicy", EmbeddedBaritonePolicy.snapshot()}, new Object[]{"worldBorder", new BetterWorldBorder(scene.getWorldBorder())},
                    new Object[]{"fallOrigin", new BlockPos(1, 1, 3)},
                    new Object[]{"fallDamageBudget", new FallDamageBudget(20, 0, 3, 1, 0, 0, 0, 0, .08, 0, false)}))
                field(CalculationContext.class, (String) value[0]).set(calc, value[1]);
            calc.backtrackCostFavoringCoefficient = 1;
            var goal = new GoalBlock(100, 1, 3); scene.feet = new BetterBlockPos(1, 1, 3);
            check(w.inventory.isEmpty() && !calc.allowBreak && !calc.hasThrowaway, "no jetpack, tools or bridge material is available");
            IPath previous = null; int segments = 0;
            while (!goal.isInGoal(scene.feet) && segments++ < 15) {
                field(CalculationContext.class, "collisionGeometry").set(calc,
                        new CollisionGeometry(scene, true, Vec3.atBottomCenterOf(scene.feet), scene.feet));
                var search = new AStarPathFinder(scene.feet, scene.feet.x, scene.feet.y, scene.feet.z, goal, new Favoring(null, calc), calc);
                var result = search.calculate(150, 1000);
                check(result.getType() == (scene.loadedThrough >= 100 ? PathCalculationResult.Type.SUCCESS_TO_GOAL
                        : PathCalculationResult.Type.SUCCESS_SEGMENT), "real search must return a safe segment until destination loads: " + result.getType());
                var path = result.getPath().orElseThrow();
                check(path.getGoal() == goal && path.getDest().getX() > scene.feet.getX(), "every segment keeps the same final goal and advances");
                for (BlockPos p : path.positions()) {
                    check(scene.isLoaded(p) && p.getY() == 1 && scene.getBlockState(p.below()).is(Blocks.STONE),
                            "walk only on loaded support; lava and the cliff are excluded: " + p);
                }
                if (previous != null) seamlessHandoff(memory, backend, ctx, goal, previous, path);
                previous = path; scene.feet = path.getDest(); scene.loadedThrough += 16;
            }
            check(goal.isInGoal(scene.feet) && segments > 2, "one ground journey reaches the strict remote destination across multiple frontiers");
            // 逃命只给威胁圈，仍用真正的地面A*绕开前方悬崖；没有喷气背包，也没有预选的32格落点。
            scene.feet = new BetterBlockPos(64, 1, 3); scene.loadedThrough = 159; scene.wide = true;
            field(CalculationContext.class, "collisionGeometry").set(calc, new CollisionGeometry(scene, true, Vec3.atBottomCenterOf(scene.feet), scene.feet));
            var escape = NavGoal.avoid(40, List.of(new GoalAvoidEntities.Threat(62.5, 1, 3.5, 3, 32)));
            Goal retreat = new Goal() {
                public boolean isInGoal(int x, int y, int z) { return escape.isAt(new BlockPos(x, y, z)); }
                public double heuristic(int x, int y, int z) { return escape.heuristic(new BlockPos(x, y, z)); }
            };
            var escaped = new AStarPathFinder(scene.feet, 64, 1, 3, retreat, new Favoring(null, calc), calc).calculate(200, 1000);
            check(escaped.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL, "directional retreat finds a real route out of the threat circle");
            for (BlockPos p : escaped.getPath().orElseThrow().positions()) check(p.getY() == 1 && scene.getBlockState(p.below()).is(Blocks.STONE),
                    "retreat is grounded and cannot cross the unsupported straight-line shortcut");
            // 初次预计算失败后，相同边界不空转；新前方区块与计算期间到来的区块都会打开重试机会。
            scene.loadedThrough = 15; scene.wide = false; var frontier = LoadedFrontier.capture(new BlockPos(14, 1, 3), scene::isLoaded);
            check(!frontier.hasNewTerrain(scene::isLoaded), "unchanged evidence cannot retry every tick");
            scene.loadedThrough = 31; check(frontier.hasNewTerrain(scene::isLoaded), "new loaded terrain resumes speculative planning before arrival");
            scene.loadedThrough = -1; check(!frontier.hasNewTerrain(scene::isLoaded), "unloading alone is not new route evidence");
        }
        System.out.println("GroundJourneyContinuationTest: passed");
    }
    private static void seamlessHandoff(Unsafe memory, Baritone backend, IPlayerContext ctx, GoalBlock goal, IPath first, IPath second) throws Exception {
        var behavior = new PathingBehavior(backend);
        var old = (Executor) memory.allocateInstance(Executor.class); old.path = first; old.finished = true;
        var next = (Executor) memory.allocateInstance(Executor.class); next.path = second;
        field(PathingBehavior.class, "current").set(behavior, old); field(PathingBehavior.class, "next").set(behavior, next);
        field(PathingBehavior.class, "goal").set(behavior, goal); field(PathingBehavior.class, "expectedSegmentStart").set(behavior, ctx.playerFeet());
        var tick = PathingBehavior.class.getDeclaredMethod("tickPath"); tick.setAccessible(true); tick.invoke(behavior);
        check(field(PathingBehavior.class, "current").get(behavior) == next && next.ticks == 1
                && field(PathingBehavior.class, "goal").get(behavior) == goal, "native path handoff drives next route in the same tick without completing the journey");
    }
    // 只代替移动输入，路径本身来自上方真正的原生 A*，从而单独检查接续调度有没有插入空刻。
    private static final class Executor extends PathExecutor {
        IPath path; boolean finished; int ticks;
        private Executor() { super(null, null); }
        @Override public boolean onTick() { ticks++; return true; }
        @Override public boolean failed() { return false; }
        @Override public boolean finished() { return finished; }
        @Override public IPath getPath() { return path; }
    }
    private static final class BlocksView extends BlockStateInterface {
        Terrain scene;
        private BlocksView() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return scene.getBlockState(new BlockPos(x, y, z)); }
        @Override public boolean isLoaded(int x, int z) { return scene.isLoaded(new BlockPos(x, 1, z)); }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return isLoaded(x, z); }
    }
    // 路中央依次放岩浆与无支撑悬崖，侧面留真实地面；未加载区域仅作不可穿越边界。
    private static final class Terrain extends ClientLevel {
        int loadedThrough; BetterBlockPos feet; boolean wide;
        private Terrain() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        // 长途接续场景逐条加载窄区块；撤退场景提供完整已知邻域，单独验证任选安全方向的路径。
        @Override public boolean isLoaded(BlockPos p) { return p.getX() >= 0 && p.getX() <= loadedThrough && p.getZ() >= (wide ? -64 : 0) && p.getZ() < (wide ? 64 : 16); }
        @Override public BlockState getBlockState(BlockPos p) {
            if (!isLoaded(p)) return Blocks.BEDROCK.defaultBlockState();
            if (p.getY() != 0) return Blocks.AIR.defaultBlockState();
            if (p.getZ() >= 2 && p.getZ() <= 4) {
                if (p.getX() >= 8 && p.getX() <= 11) return Blocks.LAVA.defaultBlockState();
                if (p.getX() >= 24 && p.getX() <= 28) return Blocks.AIR.defaultBlockState();
                if (wide && p.getX() >= 68 && p.getX() <= 72) return Blocks.AIR.defaultBlockState();
            }
            return Blocks.STONE.defaultBlockState();
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
