// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlacementConfirmation;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 区域补光的任务：开始时已满足零动作、量光选灯放灯复测达标、农田档只验收庄稼、备料失败原样透传、
 * 没处插的暗格如实收场、走不到的样本不算进分母。 */
class LightTaskTest {

    private static final String DIM = "minecraft:overworld";
    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static BlockState STONE;
    private static BlockState FARMLAND;
    private static BlockState WHEAT;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
        FARMLAND = Blocks.FARMLAND.defaultBlockState();
        WHEAT = Blocks.WHEAT.defaultBlockState();
    }

    /** 替身现场：施工接缝、照明现场与亮度全看一张世界表；放灯之后量得到新的光。 */
    private static final class Fake implements ConstructionSeams.ReadsSite, ConstructionSeams.PlansPlacement,
            ConstructionSeams.Clicks, DigsBlocks, ItemNeeds, ConstructionSeams.HoldsItem,
            ConstructionSeams.Guards, ConstructionSeams.Ledger, BringsPlayerClose,
            ConstructionSeams.Travels, LightingWorld, ReadsLightLevels {
        final Map<BlockPos, BlockState> world = new HashMap<>();
        final Set<BlockPos> loadedColumns = new HashSet<>();
        final Set<String> unobtainable = new HashSet<>();
        final Map<String, Integer> carried = new HashMap<>();
        final List<String> purposes = new ArrayList<>();

        Fake ground(int from, int to) {
            for (int x = from; x <= to; x++) {
                for (int z = from; z <= to; z++) {
                    world.put(new BlockPos(x, 63, z), STONE);
                    loadedColumns.add(new BlockPos(x, 64, z));
                }
            }
            return this;
        }

        ConstructionServices services() {
            return new ConstructionServices(this, this, this, this, this, this, this, this, this, this);
        }

        // 工地读数与照明现场共用同一张表。
        @Override public boolean loaded(BlockPos pos) {
            return loadedColumns.contains(new BlockPos(pos.getX(), 64, pos.getZ()));
        }
        @Override public BlockState state(BlockPos pos) {
            return world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public String dimension() { return DIM; }
        @Override public BlockPos feet() { return CENTER; }
        @Override public int carried(String itemId) { return carried.getOrDefault(itemId, 0); }
        @Override public boolean creative() { return false; }
        @Override public boolean unbreakable(BlockPos pos) { return false; }
        @Override public Optional<String> temporaryMaterial() { return Optional.empty(); }
        @Override public boolean sturdy(BlockPos pos, Direction face) {
            BlockState support = world.get(pos);
            return support != null && !support.isAir();
        }

        // 放置预测与出手：原版总把灯放成本来的样子。
        @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
            return Optional.of(new PlacementPrediction.Placement(clicked, face, cell.state()));
        }
        @Override public boolean requiresSneak(BlockPos clicked) { return false; }
        @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
                                     PlacementConfirmation confirmation) {
            return new Click(new Effect(() -> {
                world.put(cell.pos(), placement.predicted());
                carried.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), -1, Integer::sum);
            }), () -> InteractionResult.applied("替身确认"));
        }
        @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
            return new Click(new Effect(() -> { }), () -> InteractionResult.applied("替身确认"));
        }
        @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
            return new Click(new Effect(() -> { }), () -> InteractionResult.applied("替身确认"));
        }
        @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
            return new Click(new Effect(() -> { }), () -> InteractionResult.applied("替身确认"));
        }

        // 许可、备料、记账、走到：没有保护、没有走法（走不到就如实记没加载）。
        @Override public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
            return Optional.empty();
        }
        @Override public ConstructionSeams.HoldPlan hold(String itemId) { return new ConstructionSeams.HoldPlan.Ready(); }
        @Override public void siteStarted(Blueprint blueprint) { }
        @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) { }
        @Override public void temporaryRemoved(BlockPos pos) { }
        @Override public List<BlockPos> temporaries() { return List.of(); }
        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            return new Effect(() -> { });
        }
        @Override public Optional<Action> toward(WorldPosition where, Permissions permissions) {
            return Optional.empty();
        }

        // 拿东西：火把弄不到就如实说；拿得到就进背包，用途记下来核对。
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            String item = request.wanted().itemId();
            if (unobtainable.contains(item)) {
                return new Failing("附近找不到 " + item);
            }
            purposes.add(request.purpose());
            return new Effect(() -> carried.merge(item, request.count(), Integer::sum));
        }
        @Override public Optional<Action> dig(BlockPos target) {
            return Optional.of(new Effect(() -> world.put(target, Blocks.AIR.defaultBlockState())));
        }

        // 亮度：按世界表里的发光方块现算（发射量 − 曼哈顿距离），放灯之后量得到。
        @Override public OptionalInt blockLight(BlockPos pos) {
            if (!loadedColumns.contains(new BlockPos(pos.getX(), 64, pos.getZ()))) return OptionalInt.empty();
            int brightest = 0;
            for (Map.Entry<BlockPos, BlockState> entry : world.entrySet()) {
                int emission = entry.getValue().getLightEmission();
                if (emission > 0) {
                    brightest = Math.max(brightest, emission - manhattan(entry.getKey(), pos));
                }
            }
            return OptionalInt.of(brightest);
        }

        private static int manhattan(BlockPos a, BlockPos b) {
            return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
        }
    }

    /** 一刻做完、顺手改世界的动作。 */
    private static final class Effect implements Action {
        private final Runnable effect;
        Effect(Runnable effect) { this.effect = effect; }
        @Override public ActionStatus tick(TickContext context) { effect.run(); return ActionStatus.done(); }
        @Override public void pause() { }
        @Override public void close() { }
        @Override public String describe() { return "替身动作"; }
    }

    /** 一刻失败的动作。 */
    private static final class Failing implements Action {
        private final String why;
        Failing(String why) { this.why = why; }
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, why));
        }
        @Override public void pause() { }
        @Override public void close() { }
        @Override public String describe() { return "替身失败"; }
    }

    private static TaskResult run(LightTask task) {
        task.start(TickContext.of(0, null));
        for (long tick = 1; tick <= 20000; tick++) {
            long now = tick;
            TickResult result = task.tick(new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            });
            if (result instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("两万刻还没收场");
    }

    private static LightTask task(LightInput input, Fake fake) {
        return new LightTask(input, fake.services(), fake, fake);
    }

    private static LightInput input(int radius, LightInput.Coverage coverage, int minimumLight) {
        return new LightInput(new WorldPosition(CENTER.getX(), CENTER.getY(), CENTER.getZ(), DIM),
                radius, null, coverage, minimumLight, Permissions.DEFAULT);
    }

    @Test
    void 开始时已满足_一支灯都不插() {
        Fake fake = new Fake().ground(-2, 2);
        // 圆心放一支火把：发射 14，半径 2 的圆全在光里。
        fake.world.put(new BlockPos(0, 64, 0), Blocks.TORCH.defaultBlockState());
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().contains("开始时已满足"));
        assertTrue(result.changes().isEmpty(), "零动作");
        LightDetails details = (LightDetails) result.details();
        assertEquals(0, details.placed());
        assertEquals("minecraft:torch", details.source(), "没给 item 默认火把");
    }

    @Test
    void 量光选灯放灯复测_全达标() {
        Fake fake = new Fake().ground(-6, 6);
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        LightDetails details = (LightDetails) result.details();
        assertTrue(details.placed() > 0, "插了灯");
        assertEquals(details.samples(), details.lit(), "复测全部达标");
        assertEquals(1.0, details.coverageAchieved());
        assertTrue(fake.purposes.contains("照明"), "备料带照明的用途标签");
    }

    @Test
    void 农田档只验收庄稼与耕地_种着的庄稼上不落灯() {
        Fake fake = new Fake().ground(-6, 6);
        // 两格农田，其中一格种着麦子。
        for (int x = -1; x <= 0; x++) {
            fake.world.put(new BlockPos(x, 63, 0), FARMLAND);
            fake.world.put(new BlockPos(x, 64, 0), x == -1 ? WHEAT : Blocks.AIR.defaultBlockState());
        }
        TaskResult result = run(task(input(2, LightInput.Coverage.CROPS, 9), fake));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        LightDetails details = (LightDetails) result.details();
        assertTrue(details.samples() >= 2, "庄稼格与耕地上方都进了样本");
        assertTrue(!isTorch(fake, new BlockPos(-1, 64, 0)), "庄稼那格不落灯");
        assertEquals(FARMLAND, fake.world.get(new BlockPos(-1, 63, 0)), "耕地没被改动");
    }

    private static boolean isTorch(Fake fake, BlockPos pos) {
        BlockState state = fake.world.get(pos);
        return state != null && state.is(Blocks.TORCH);
    }

    @Test
    void 备料弄不到_原样透传导料证据_不冒充灯位不足() {
        Fake fake = new Fake().ground(-6, 6);
        fake.unobtainable.add("minecraft:torch");
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind(), "透传导料的问题");
        assertTrue(result.problem().message().contains("minecraft:torch"), "缺什么写清");
    }

    @Test
    void 范围里站不进一格_如实结束写明查过哪里() {
        Fake fake = new Fake().ground(-6, 6);
        // 整片堆到采样窗口顶（y63..70）的箱子：没有一格能站住验收，连样本都采不出来。
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                for (int y = 63; y <= 70; y++) {
                    fake.world.put(new BlockPos(x, y, z), Blocks.CHEST.defaultBlockState());
                }
            }
        }
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains("样本"), result.problem().message());
    }

    @Test
    void 走不到的样本不算进分母_不给全部达标的结论() {
        Fake withUnloaded = new Fake().ground(-6, 6);
        withUnloaded.loadedColumns.remove(new BlockPos(1, 64, 1));
        TaskResult partial = run(task(input(2, LightInput.Coverage.ALL, 8), withUnloaded));
        assertEquals(TaskResult.Status.PARTIAL, partial.status(), partial.summary());
        assertTrue(partial.summary().contains("没加载"), partial.summary());
        LightDetails details = (LightDetails) partial.details();
        assertEquals(LightDetails.UNLOADED, details.unlit().getFirst().reason());
        assertEquals(1.0, details.coverageAchieved(), "能验收的都达标，分母是能验收的样本");
        assertTrue(partial.problem() == null, "能验收的全亮了，不冤枉它");
    }

    @Test
    void 照明不挖一格_蓝图没有要清空的格子() {
        Fake fake = new Fake().ground(-6, 6);
        run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        // 照明不声明 AIR 格：整场施工一次挖掘都不发生。
        for (Map.Entry<BlockPos, BlockState> entry : fake.world.entrySet()) {
            if (entry.getKey().getY() == 63) {
                assertEquals(STONE, entry.getValue(), "地板没被挖过");
            }
        }
    }
}
