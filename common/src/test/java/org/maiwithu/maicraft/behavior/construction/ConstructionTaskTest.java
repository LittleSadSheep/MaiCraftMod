// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 施工任务用替身跑完整个流程：已满足不动手、砌与清障、受保护的格、材料尽头、垫临时方块再收回、倒桶、开关门。 */
class ConstructionTaskTest {

    private static final String DIM = "minecraft:overworld";
    private static BlockState STONE;
    private static BlockState AIR;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 方块注册表要等引导完才能碰：静态字段在这里才赋值，不在类加载时碰 Blocks。
        STONE = Blocks.STONE.defaultBlockState();
        AIR = Blocks.AIR.defaultBlockState();
    }

    /** 替身工地：一张世界表、身上的东西、脚下的格；什么都能一刻做完。 */
    private static final class Fake implements ConstructionSeams.ReadsSite, ConstructionSeams.PlansPlacement, ConstructionSeams.Clicks,
            DigsBlocks, ItemNeeds, ConstructionSeams.HoldsItem, ConstructionSeams.Guards, ConstructionSeams.Ledger, BringsPlayerClose {
        final Map<BlockPos, BlockState> world = new HashMap<>();
        final Map<String, Integer> carried = new HashMap<>();
        final Set<BlockPos> protectedCells = new HashSet<>();
        final Set<String> unobtainable = new HashSet<>();
        final List<BlockPos> temporaries = new ArrayList<>();
        final List<String> siteNotes = new ArrayList<>();
        BlockPos feet = new BlockPos(5, 64, 5);

        Fake ground(int y) {
            for (int x = -3; x <= 8; x++) for (int z = -3; z <= 8; z++) world.put(new BlockPos(x, y, z), STONE);
            return this;
        }

        @Override public boolean loaded(BlockPos pos) { return true; }
        @Override public BlockState state(BlockPos pos) { return world.getOrDefault(pos, AIR); }
        @Override public String dimension() { return DIM; }
        @Override public BlockPos feet() { return feet; }
        @Override public int carried(String itemId) { return carried.getOrDefault(itemId, 0); }
        @Override public boolean creative() { return false; }
        @Override public boolean unbreakable(BlockPos pos) { return state(pos).is(Blocks.BEDROCK); }
        @Override public Optional<String> temporaryMaterial() {
            return carried("minecraft:cobblestone") > 0 ? Optional.of("minecraft:cobblestone") : Optional.empty();
        }

        @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
            BlockState predicted = cell.state();
            // 替身的原版：门总是关着放下来，开关由验收去按。
            if (predicted.hasProperty(BlockStateProperties.OPEN)) predicted = predicted.setValue(BlockStateProperties.OPEN, false);
            return Optional.of(new PlacementPrediction.Placement(clicked, face, predicted));
        }
        @Override public boolean requiresSneak(BlockPos clicked) { return false; }

        @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak, PlacementConfirmation confirmation) {
            return click(() -> {
                world.put(cell.pos(), placement.predicted());
                for (var effect : PlacementPrediction.generatedBy(cell.pos(), placement.predicted())) world.put(effect.pos(), effect.expected());
                carried.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), -1, Integer::sum);
            });
        }
        @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
            return click(() -> {
                world.put(cell.pos(), cell.state());
                carried.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), -1, Integer::sum);
                carried.merge("minecraft:bucket", 1, Integer::sum);
            });
        }
        @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
            return click(() -> world.put(source, AIR));
        }
        @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
            return click(() -> world.put(block, state(block).cycle(BlockStateProperties.OPEN)));
        }
        private Click click(Runnable effect) {
            return new Click(new Immediate(effect), () -> InteractionResult.applied("替身确认"));
        }

        @Override public Optional<Action> dig(BlockPos target) { return Optional.of(new Immediate(() -> world.put(target, AIR))); }
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            String item = request.wanted().itemId();
            if (unobtainable.contains(item)) return new Failing("附近找不到 " + item);
            return new Immediate(() -> carried.merge(item, request.count(), Integer::sum));
        }
        @Override public ConstructionSeams.HoldPlan hold(String itemId) { return new ConstructionSeams.HoldPlan.Ready(); }
        @Override public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
            return protectedCells.contains(pos) ? Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL, pos.toShortString() + " 是玩家的东西")) : Optional.empty();
        }
        @Override public void siteStarted(Blueprint blueprint) { siteNotes.add("开工"); }
        @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) { temporaries.add(pos); }
        @Override public void temporaryRemoved(BlockPos pos) { temporaries.remove(pos); }
        @Override public List<BlockPos> temporaries() { return List.copyOf(temporaries); }
        @Override public Action toward(ApproachTarget target, Permissions permissions) { return new Immediate(() -> { }); }

        ConstructionServices services() {
            return new ConstructionServices(this, this, this, this, this, this, this, this, this, null);
        }
    }

    /** 一刻做完的动作。 */
    private record Immediate(Runnable effect) implements Action {
        @Override public ActionStatus tick(TickContext context) { effect.run(); return ActionStatus.done(); }
        @Override public String describe() { return "替身动作"; }
    }

    private record Failing(String why) implements Action {
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, why)); }
        @Override public String describe() { return "替身失败"; }
    }

    private static TaskResult run(Fake fake, Blueprint blueprint) {
        Task task = new ConstructionTask(new ConstructionInput(blueprint, Permissions.DEFAULT, "施工"), fake.services());
        for (long tick = 1; tick <= 5000; tick++) {
            long now = tick;
            TickResult result = task.tick(new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            });
            if (result instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("五千刻还没做完");
    }

    private static Blueprint wall(BlockState state, BlockPos... cells) {
        List<PlannedCell> planned = new ArrayList<>();
        for (BlockPos pos : cells) planned.add(PlannedCell.block(pos, state, Set.of()));
        return new Blueprint(DIM, cells[0], planned);
    }

    private static long count(TaskResult result, Change.Kind kind) {
        return result.changes().stream().filter(change -> change.kind() == kind).count();
    }

    private static ConstructionDetails details(TaskResult result) {
        return (ConstructionDetails) result.details();
    }

    @Test
    void 开始时已满足_一个动作都不做() {
        Fake fake = new Fake().ground(63);
        fake.world.put(new BlockPos(0, 64, 0), STONE);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.changes().isEmpty());
        assertEquals(1, details(result).cells().get("matches"));
        assertEquals(List.of("开工"), fake.siteNotes);
    }

    @Test
    void 砌两格_用掉两块石头() {
        Fake fake = new Fake().ground(63);
        fake.carried.put("minecraft:stone", 2);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0), new BlockPos(0, 65, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(2, count(result, Change.Kind.BLOCK_PLACED));
        assertEquals(2, details(result).cells().get("placed"));
        assertEquals(2, details(result).materialsUsed().get("minecraft:stone"));
        assertEquals(0, fake.carried("minecraft:stone"));
    }

    @Test
    void 挡着的先清掉再放() {
        Fake fake = new Fake().ground(63);
        fake.world.put(new BlockPos(0, 64, 0), Blocks.DIRT.defaultBlockState());
        fake.carried.put("minecraft:stone", 1);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, count(result, Change.Kind.BLOCK_BROKEN));
        assertEquals(1, count(result, Change.Kind.BLOCK_PLACED));
    }

    @Test
    void 受保护的格不动_一次列出() {
        Fake fake = new Fake().ground(63);
        fake.world.put(new BlockPos(1, 64, 0), Blocks.DIRT.defaultBlockState());
        fake.protectedCells.add(new BlockPos(1, 64, 0));
        fake.carried.put("minecraft:stone", 2);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_APPROVAL, result.problem().kind());
        assertEquals(1, details(result).cells().get("protected"));
        assertEquals(1, details(result).cells().get("placed"), "其余照建");
        assertEquals(Blocks.DIRT, fake.state(new BlockPos(1, 64, 0)).getBlock(), "受保护的格没动");
    }

    @Test
    void 材料用完停在材料尽头() {
        Fake fake = new Fake().ground(63);
        fake.carried.put("minecraft:stone", 1);
        fake.unobtainable.add("minecraft:stone");
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertEquals(1, details(result).cells().get("placed"));
        assertEquals(1, details(result).cells().get("missing"));
        assertTrue(details(result).problems().stream().anyMatch(group -> group.reason().equals("缺 minecraft:stone")));
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("找不到")), "备料失败记在尝试里");
    }

    @Test
    void 够不着就垫临时方块_完了收回() {
        Fake fake = new Fake().ground(66);
        fake.carried.put("minecraft:stone", 1);
        fake.carried.put("minecraft:cobblestone", 8);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 70, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(STONE, fake.state(new BlockPos(0, 70, 0)));
        assertEquals(4, count(result, Change.Kind.BLOCK_PLACED), "三块垫的加一块目标");
        assertEquals(3, count(result, Change.Kind.BLOCK_BROKEN), "垫的三块都收回");
        assertTrue(fake.temporaries.isEmpty());
        assertEquals(AIR, fake.state(new BlockPos(0, 68, 0)));
        assertTrue(details(result).temporaryBlocksLeft().isEmpty());
    }

    @Test
    void 倒桶() {
        Fake fake = new Fake().ground(63);
        fake.carried.put("minecraft:water_bucket", 1);
        Blueprint blueprint = new Blueprint(DIM, new BlockPos(0, 64, 0), List.of(PlannedCell.block(new BlockPos(0, 64, 0), Blocks.WATER.defaultBlockState(), Set.of())));
        TaskResult result = run(fake, blueprint);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, details(result).cells().get("poured"));
        assertEquals(1, count(result, Change.Kind.ITEM_CONSUMED));
        assertEquals(1, fake.carried("minecraft:bucket"));
    }

    @Test
    void 点名了开着的门_放好后按一下() {
        Fake fake = new Fake().ground(63);
        fake.carried.put("minecraft:oak_door", 1);
        BlockState open = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        Blueprint blueprint = new Blueprint(DIM, new BlockPos(0, 64, 0), List.of(PlannedCell.block(new BlockPos(0, 64, 0), open, Set.of("open"))));
        TaskResult result = run(fake, blueprint);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(fake.state(new BlockPos(0, 64, 0)).getValue(BlockStateProperties.OPEN));
        assertEquals(1, count(result, Change.Kind.BLOCK_CHANGED), "按了一次开关");
    }
}
