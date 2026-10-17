// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 区域补光的任务：开始时已满足零动作、量光选灯放灯复测达标、农田档只验收庄稼、缺灯不采购如实报缺口、
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
        /** 走得过去：走到没加载的列，那一列就加载了；记下走了哪几列。 */
        boolean walkLoads;
        final List<BlockPos> walkedTo = new ArrayList<>();
        /** 施工走近灯位时交出的动作；没给就一刻走到。用来演"施工正走在半路"。 */
        Action approach;

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
            return approach != null ? approach : new Effect(() -> { });
        }
        @Override public Optional<Action> toward(WorldPosition where, Permissions permissions) {
            if (!walkLoads) return Optional.empty();
            BlockPos column = new BlockPos(where.x(), 64, where.z());
            return Optional.of(new Effect(() -> {
                walkedTo.add(column);
                loadedColumns.add(column);
            }));
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

        // 天空光：这些场景都在地下，头顶遮着。
        @Override public OptionalInt skyLight(BlockPos pos) {
            if (!loadedColumns.contains(new BlockPos(pos.getX(), 64, pos.getZ()))) return OptionalInt.empty();
            return OptionalInt.of(0);
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

    @Test
    void 插灯施工跟着补光暂停与收尾_能不能停看施工手上的() {
        // 插灯交施工引擎按动作推进：施工正走向灯位时，补光的暂停、收尾转到施工手上，能不能停也看它的。
        Fake fake = new Fake().ground(-6, 6);
        fake.carried.put("minecraft:torch", 64);
        Held walking = new Held();
        fake.approach = walking;
        LightTask task = task(input(2, LightInput.Coverage.ALL, 8), fake);
        task.start(TickContext.of(0, null));
        for (long tick = 1; tick <= 400 && walking.ticks == 0; tick++) {
            assertFalse(task.tick(TickContext.of(tick, null)) instanceof TickResult.Finished, "补光没走到插灯就收场了");
        }
        assertTrue(walking.ticks > 0, "施工走在半路");
        assertTrue(task.describe().contains("插灯"), task.describe());

        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(TickContext.of(401, null)),
                "施工手上那一下停不得，补光就停不得");
        task.pause();
        assertEquals(1, walking.pauses, "补光暂停，施工跟着停下松手");
        task.close(CloseReason.CANCELLED);
        assertEquals(1, walking.closes, "补光收尾，施工跟着收尾");
    }

    /** 一直在走、停不得的一下：记下被推进、暂停、收尾了几次。 */
    private static final class Held implements Action {
        int ticks;
        int pauses;
        int closes;

        @Override public ActionStatus tick(TickContext context) {
            ticks++;
            return ActionStatus.running();
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public void close() {
            closes++;
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.UNSAFE_TO_STOP;
        }

        @Override public String describe() {
            return "停不得的一下";
        }
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
        fake.carried.put("minecraft:torch", 64);
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        LightDetails details = (LightDetails) result.details();
        assertTrue(details.placed() > 0, "插了灯");
        assertEquals(details.samples(), details.lit(), "复测全部达标");
        assertEquals(1.0, details.coverageAchieved());
        assertTrue(fake.purposes.isEmpty(), "身上的灯够用，不去弄灯");
    }

    @Test
    void 农田档只验收庄稼与耕地_种着的庄稼上不落灯() {
        Fake fake = new Fake().ground(-6, 6);
        fake.carried.put("minecraft:torch", 8);
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
    void 身上一支灯都没有_如实报缺_不去弄灯() {
        Fake fake = new Fake().ground(-6, 6);
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind(), "缺灯是物品缺口，不是没地方插");
        assertTrue(result.problem().message().contains("minecraft:torch"), "缺什么写清");
        assertTrue(result.summary().contains("一支灯都插不出去"), result.summary());
        assertTrue(fake.purposes.isEmpty(), "light 不采购：备料接缝一次都不被问");
    }

    @Test
    void 火把不够照完_用完手上的_partial写明还缺多少() {
        Fake fake = new Fake().ground(-6, 6);
        fake.carried.put("minecraft:torch", 1);
        TaskResult result = run(task(input(6, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("还缺"), result.problem().message());
        LightDetails details = (LightDetails) result.details();
        assertEquals(1, details.placed(), "手上的火把用上了");
        assertTrue(fake.purposes.isEmpty(), "缺了也不去弄灯，采购不发生在 light 里");
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
        withUnloaded.carried.put("minecraft:torch", 8);
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
    void 没加载的列走过去就重量_不来回走() {
        Fake fake = new Fake().ground(-6, 6);
        fake.carried.put("minecraft:torch", 8);
        BlockPos far = new BlockPos(1, 64, 1);
        fake.loadedColumns.remove(far);
        fake.walkLoads = true;
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        // 走过去那一列加载了，连它一起重量、照亮：全部达标，只走了这一趟。
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertEquals(List.of(far), fake.walkedTo);
    }

    @Test
    void 量得到的都亮_可有列没加载走不到_不说开始时已满足() {
        Fake fake = new Fake().ground(-6, 6);
        fake.world.put(new BlockPos(0, 64, 0), Blocks.TORCH.defaultBlockState());
        fake.loadedColumns.remove(new BlockPos(1, 64, 1));
        TaskResult result = run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertTrue(result.summary().contains("没加载"), result.summary());
    }

    @Test
    void 照明不挖一格_蓝图没有要清空的格子() {
        Fake fake = new Fake().ground(-6, 6);
        fake.carried.put("minecraft:torch", 64);
        run(task(input(2, LightInput.Coverage.ALL, 8), fake));
        // 照明不声明 AIR 格：整场施工一次挖掘都不发生。
        for (Map.Entry<BlockPos, BlockState> entry : fake.world.entrySet()) {
            if (entry.getKey().getY() == 63) {
                assertEquals(STONE, entry.getValue(), "地板没被挖过");
            }
        }
    }
}
