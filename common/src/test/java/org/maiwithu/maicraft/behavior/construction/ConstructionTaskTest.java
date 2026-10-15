// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
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
import net.minecraft.world.level.block.DoorBlock;
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
        /** 只有蹲着才放得出要的样子的格（机械动力的方向机器）；放的时候记下这一下蹲没蹲。 */
        final Set<BlockPos> crouchOnly = new HashSet<>();
        final Map<BlockPos, Boolean> sneakedAt = new HashMap<>();
        /** 原版放出来会是这个样子的格（朝向不对）；拿扳手点一下转成蓝图里的样子。 */
        final Map<BlockPos, BlockState> placesAs = new HashMap<>();
        final List<Direction> turnedFaces = new ArrayList<>();
        final Set<String> unobtainable = new HashSet<>();
        final List<BlockPos> temporaries = new ArrayList<>();
        final List<String> siteNotes = new ArrayList<>();
        /** 装了的连锁挖替身；为空时连锁挖一格就是普通地挖。 */
        Chain chain;
        final List<DismantleTool> dismantlers = new ArrayList<>();
        final List<PlacementRules> rules = new ArrayList<>();
        /** 每次放的放法与放到的格，按出手顺序。 */
        final List<PlacementPrediction.Placement> clicks = new ArrayList<>();
        final List<BlockPos> placedOrder = new ArrayList<>();
        /** 倒过的桶（物品名），按倒的先后。 */
        final List<String> pouredItems = new ArrayList<>();
        /** 点下去方块落成了、交互却报出岔子的格：模拟确认说不清，方块其实已经在那里。 */
        final Set<BlockPos> clickFailsAfterPlacing = new HashSet<>();
        /** 像保护判断里"像玩家放的门"那条推断：现场是门就当别人的东西，归属记录还没到。 */
        boolean doorsLookPlayerMade;
        int digCalls;
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
            if (placesAs.containsKey(cell.pos())) predicted = placesAs.get(cell.pos());
            return Optional.of(new PlacementPrediction.Placement(clicked, face, predicted, crouchOnly.contains(cell.pos())));
        }
        @Override public boolean requiresSneak(BlockPos clicked) { return false; }
        // 替身转法：放出来的样子点一下东面就转成蓝图里的样子（拿木棍当扳手）。
        @Override public Optional<PlacementRules.Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
            for (var wrong : placesAs.entrySet()) {
                if (wrong.getValue().equals(placed)) return Optional.of(new PlacementRules.Turns(Items.STICK, List.of(Direction.EAST)));
            }
            return Optional.empty();
        }
        @Override public Click turn(BlockPos block, Direction face, InteractionConfirmation confirmation) {
            return click(() -> {
                turnedFaces.add(face);
                world.put(block, turnTo.getOrDefault(block, state(block)));
            });
        }
        final Map<BlockPos, BlockState> turnTo = new HashMap<>();

        @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak, PlacementConfirmation confirmation) {
            Runnable effect = () -> {
                sneakedAt.put(cell.pos(), sneak);
                clicks.add(placement);
                placedOrder.add(cell.pos());
                world.put(cell.pos(), placement.predicted());
                for (var half : PlacementPrediction.generatedBy(cell.pos(), placement.predicted())) world.put(half.pos(), half.expected());
                carried.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), -1, Integer::sum);
            };
            if (clickFailsAfterPlacing.contains(cell.pos())) return new Click(new FailingAfter(effect, "交互结果出乎预料"), () -> null);
            return click(effect);
        }
        @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
            return click(() -> {
                pouredItems.add(BuiltInRegistries.ITEM.getKey(cell.item()).getPath());
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

        @Override public Optional<Action> dig(BlockPos target) {
            digCalls++;
            return Optional.of(new Immediate(() -> world.put(target, AIR)));
        }
        // 连锁挖替身：模组那一批每一格清障都同意才整批挖掉，不然和普通地挖一样只挖触发格。
        @Override public boolean chains() { return chain != null; }
        @Override public Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
                Predicate<BlockPos> mayBreak) {
            if (chain == null) return dig(trigger);
            List<BlockPos> batch = chain.batchFrom(trigger, face);
            if (batch.size() < 2 || !batch.stream().allMatch(mayBreak)) return dig(trigger);
            return Optional.of(new Immediate(() -> {
                chain.batches++;
                for (BlockPos pos : batch) world.put(pos, AIR);
            }));
        }
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            String item = request.wanted().itemId();
            if (unobtainable.contains(item)) return new Failing("附近找不到 " + item);
            return new Immediate(() -> carried.merge(item, request.count(), Integer::sum));
        }
        @Override public ConstructionSeams.HoldPlan hold(String itemId) { return new ConstructionSeams.HoldPlan.Ready(); }
        @Override public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
            boolean guessed = doorsLookPlayerMade && state(pos).getBlock() instanceof DoorBlock;
            return protectedCells.contains(pos) || guessed
                    ? Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL, pos.toShortString() + " 是玩家的东西")) : Optional.empty();
        }
        @Override public void siteStarted(Blueprint blueprint) { siteNotes.add("开工"); }
        @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) { temporaries.add(pos); }
        @Override public void temporaryRemoved(BlockPos pos) { temporaries.remove(pos); }
        @Override public List<BlockPos> temporaries() { return List.copyOf(temporaries); }
        @Override public Action toward(ApproachTarget target, Permissions permissions) { return new Immediate(() -> { }); }

        ConstructionServices services() {
            return new ConstructionServices(this, this, this, this, this, this, this, this, this, null, dismantlers, rules);
        }
    }

    /** 一刻做完的动作。 */
    private record Immediate(Runnable effect) implements Action {
        @Override public ActionStatus tick(TickContext context) { effect.run(); return ActionStatus.done(); }
        @Override public String describe() { return "替身动作"; }
    }

    /** 先把方块落下去、再报出岔子的动作：世界变了，交互的结论却说不清。 */
    private record FailingAfter(Runnable effect, String why) implements Action {
        @Override public ActionStatus tick(TickContext context) {
            effect.run();
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, why));
        }
        @Override public String describe() { return "替身出岔子"; }
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
    void 只有蹲着放得出要的样子_就蹲着点() {
        // 机械动力的方向机器蹲着朝视线、站着背着视线：试算说蹲着才对，出手就蹲着；别的格照常站着点。
        Fake fake = new Fake().ground(63);
        fake.carried.put("minecraft:stone", 2);
        fake.crouchOnly.add(new BlockPos(0, 65, 0));
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0), new BlockPos(0, 65, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(Boolean.TRUE, fake.sneakedAt.get(new BlockPos(0, 65, 0)));
        assertEquals(Boolean.FALSE, fake.sneakedAt.get(new BlockPos(0, 64, 0)));
    }

    @Test
    void 放出来朝向不对_能拿扳手转到位就照放再转() {
        // 机械动力的方向机器：站着蹲着都放不出要的朝向，放下后拿扳手点一面转到位，记一笔转动。
        Fake fake = new Fake().ground(63);
        BlockPos at = new BlockPos(0, 64, 0);
        BlockState wanted = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN);
        BlockState placed = wanted.setValue(BlockStateProperties.FACING, Direction.SOUTH);
        fake.carried.put("minecraft:observer", 1);
        fake.carried.put("minecraft:stick", 1);
        fake.placesAs.put(at, placed);
        fake.turnTo.put(at, wanted);
        // 蓝图写明了朝向：没写的属性不比，写了的才要转到位。
        TaskResult result = run(fake, new Blueprint(DIM, at, List.of(PlannedCell.block(at, wanted, Set.of("facing")))));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(wanted, fake.world.get(at));
        assertEquals(List.of(Direction.EAST), fake.turnedFaces);
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_CHANGED));
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
    void 同一层先放离人近的_站一处放完再挪() {
        // 施工顺序本来是 x 从小到大；人站在 x=5 那头，先放够得着的 x=4，再去 x=0。
        Fake fake = new Fake().ground(63);
        fake.feet = new BlockPos(5, 64, 1);
        fake.carried.put("minecraft:stone", 2);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0), new BlockPos(4, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(new BlockPos(4, 64, 0), new BlockPos(0, 64, 0)), fake.placedOrder);
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
    void 同一层按蓝图里写的先后倒桶() {
        // 刷石机写了先岩浆后水：照写的先后倒，不按坐标排（按坐标是先倒 x=0 的水）。
        Fake fake = new Fake().ground(63);
        fake.carried.put("minecraft:water_bucket", 1);
        fake.carried.put("minecraft:lava_bucket", 1);
        Blueprint blueprint = new Blueprint(DIM, new BlockPos(0, 64, 0), List.of(
                PlannedCell.block(new BlockPos(2, 64, 0), Blocks.LAVA.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(0, 64, 0), Blocks.WATER.defaultBlockState(), Set.of())));
        TaskResult result = run(fake, blueprint);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of("lava_bucket", "water_bucket"), fake.pouredItems);
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
    void 门其实装上了只是交互报出岔子_先认对上不当受保护也不垫临时方块() {
        // 直播里第一扇门装在了这一格，结果却说出乎预料，又在旁边垫了一块，最后说这格是玩家放的不动。
        Fake fake = new Fake().ground(63);
        BlockPos at = new BlockPos(0, 64, 0);
        fake.carried.put("minecraft:spruce_door", 2);
        fake.carried.put("minecraft:cobblestone", 8);
        fake.clickFailsAfterPlacing.add(at);
        fake.doorsLookPlayerMade = true;
        TaskResult result = run(fake, new Blueprint(DIM, at, List.of(PlannedCell.block(at, Blocks.SPRUCE_DOOR.defaultBlockState(), Set.of()))));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, details(result).cells().get("protected"), "自己刚装好的门不算玩家的东西");
        assertTrue(details(result).problems().isEmpty(), "对上了的格不留问题");
        assertEquals(1, fake.clicks.size(), "门已经在了，不从别的面再点");
        assertEquals(1, details(result).cells().get("placed"), "这一下点成的，照放好记");
        assertEquals(1, count(result, Change.Kind.BLOCK_PLACED));
        assertEquals(8, fake.carried("minecraft:cobblestone"), "不为它垫临时方块");
        assertTrue(fake.temporaries.isEmpty());
    }

    @Test
    void 早先记成受保护的格_验收时现场对上了就按对上收下() {
        // 开工时那一格挡着、被当成别人的；后来别人换成了要的方块：验收先认对上，不再报受保护。
        Fake fake = new Fake().ground(63);
        BlockPos at = new BlockPos(0, 64, 0);
        fake.world.put(at, Blocks.DIRT.defaultBlockState());
        fake.protectedCells.add(at);
        Blueprint blueprint = wall(STONE, at);
        ConstructionSite site = new ConstructionSite(blueprint);
        site.check(fake);
        site.exclude(at, CellEnding.PROTECTED, "是玩家的东西");
        fake.world.put(at, STONE);
        site.check(fake);
        assertEquals(CellEnding.MATCHES, site.ending(at));
        assertTrue(site.allSettled());
        assertTrue(site.problemGroups().isEmpty());
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

    /** 连锁挖替身：命中面 3×3 里触发格连着它东边一格一起挖，只在触发格是 (0,64,0) 时连。 */
    private static final class Chain {
        int batches;
        /** 像 FTB 的小型方形：命中面上以触发格为中心的 3×3。为假时只认 (0,64,0) 连着东边一格。 */
        boolean square;
        List<BlockPos> batchFrom(BlockPos trigger, Direction face) {
            if (!square) return trigger.equals(new BlockPos(0, 64, 0)) ? List.of(trigger, trigger.east()) : List.of();
            List<BlockPos> batch = new ArrayList<>();
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    // 点顶面是水平的一片；没指定面时按点侧面算，是竖着的一片（会伸到地面以上）。
                    batch.add(face == Direction.UP ? trigger.offset(a, 0, b) : trigger.offset(a, b, 0));
                }
            }
            return batch;
        }
    }

    @Test
    void 连锁清障_先挑整片中心对准顶面() {
        // 3×3 一层土要换成石头：先挑中间那格对准顶面挖，模组的 3×3 正好是这一片，一下挖掉，不从角上挖起。
        Fake fake = new Fake().ground(63);
        Chain chain = new Chain();
        chain.square = true;
        fake.chain = chain;
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                BlockPos pos = new BlockPos(x, 64, z);
                fake.world.put(pos, Blocks.DIRT.defaultBlockState());
                cells.add(pos);
            }
        }
        fake.carried.put("minecraft:stone", 9);
        TaskResult result = run(fake, wall(STONE, cells.toArray(BlockPos[]::new)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, chain.batches, "整片一下挖掉");
        assertEquals(0, fake.digCalls, "没有逐格挖");
        assertEquals(9, count(result, Change.Kind.BLOCK_BROKEN));
    }

    @Test
    void 连锁挖_整批都是要清的格才用() {
        Fake fake = new Fake().ground(63);
        Chain chain = new Chain();
        fake.chain = chain;
        for (int x = 0; x <= 1; x++) fake.world.put(new BlockPos(x, 64, 0), Blocks.DIRT.defaultBlockState());
        fake.carried.put("minecraft:stone", 2);
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, chain.batches, "两格都要清，一批挖掉");
        assertEquals(0, fake.digCalls, "没有逐格挖");
        assertEquals(2, count(result, Change.Kind.BLOCK_BROKEN), "连锁顺带挖掉的那一格也逐格结算");
        assertEquals(2, count(result, Change.Kind.BLOCK_PLACED));
    }

    @Test
    void 连锁挖的批里有一格不是要清的_整批不用() {
        Fake fake = new Fake().ground(63);
        Chain chain = new Chain();
        fake.chain = chain;
        for (int x = 0; x <= 1; x++) fake.world.put(new BlockPos(x, 64, 0), Blocks.DIRT.defaultBlockState());
        fake.carried.put("minecraft:stone", 1);
        // 蓝图只要第一格，东边那格泥土不是要清的：模组按形状一起挖没法跳过它，所以整批不用。
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, chain.batches);
        assertEquals(1, fake.digCalls, "退回只挖这一格");
        assertEquals(Blocks.DIRT, fake.state(new BlockPos(1, 64, 0)).getBlock(), "不该挖的格没动");
    }

    @Test
    void 模组工具认领的方块整块拆下() {
        Fake fake = new Fake().ground(63);
        fake.world.put(new BlockPos(0, 64, 0), Blocks.DIRT.defaultBlockState());
        fake.carried.put("minecraft:stone", 1);
        fake.dismantlers.add(new DismantleTool() {
            @Override public Optional<Item> toolFor(BlockState state) {
                return state.is(Blocks.DIRT) ? Optional.of(Items.STICK) : Optional.empty();
            }
            @Override public Optional<Action> dismantle(BlockPos cell) {
                return Optional.of(new Immediate(() -> {
                    fake.world.put(cell, AIR);
                    fake.carried.merge("minecraft:dirt", 1, Integer::sum);
                }));
            }
        });
        TaskResult result = run(fake, wall(STONE, new BlockPos(0, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, fake.digCalls, "认领了就用工具拆，不挖");
        assertEquals(1, count(result, Change.Kind.BLOCK_BROKEN));
        assertEquals(1, fake.carried("minecraft:dirt"), "拆下的东西进了背包");
    }

    /** 放置规则替身：认一种方块，说拿什么放、对着哪一格放、要等哪几格。 */
    private record Rule(BlockState block, Item item, BlockPos placeAtOffset, List<BlockPos> after) implements PlacementRules {
        @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
            return state.is(block.getBlock()) ? Optional.of(new Placement(item, at.offset(placeAtOffset))) : Optional.empty();
        }
        @Override public List<BlockPos> placeAfter(BlockState state) {
            return state.is(block.getBlock()) ? after : List.of();
        }
    }

    @Test
    void 规则说拿别的物品放_备料换手记账都认它() {
        Fake fake = new Fake().ground(63);
        fake.rules.add(new Rule(Blocks.TINTED_GLASS.defaultBlockState(), Items.GLASS, BlockPos.ZERO, List.of()));
        fake.carried.put("minecraft:glass", 1);
        TaskResult result = run(fake, wall(Blocks.TINTED_GLASS.defaultBlockState(), new BlockPos(0, 64, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(Blocks.TINTED_GLASS, fake.state(new BlockPos(0, 64, 0)).getBlock());
        assertEquals(1, details(result).materialsUsed().get("minecraft:glass"));
        assertEquals(0, fake.carried("minecraft:glass"));
    }

    @Test
    void 要等的格先放_等它的格回头再放() {
        Fake fake = new Fake().ground(63);
        // 木板要等东边的火把先放好：照常的顺序木板是骨架、先放，有了规则就先放一放。
        fake.rules.add(new Rule(Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, BlockPos.ZERO, List.of(new BlockPos(1, 0, 0))));
        fake.carried.put("minecraft:oak_planks", 1);
        fake.carried.put("minecraft:torch", 1);
        List<PlannedCell> cells = List.of(PlannedCell.block(new BlockPos(0, 64, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 64, 0), Blocks.TORCH.defaultBlockState(), Set.of()));
        TaskResult result = run(fake, new Blueprint(DIM, new BlockPos(0, 64, 0), cells));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(0, 64, 0)), fake.placedOrder);
    }

    @Test
    void 等的格没放成_等它的格如实记下() {
        Fake fake = new Fake().ground(63);
        fake.rules.add(new Rule(Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, BlockPos.ZERO, List.of(new BlockPos(1, 0, 0))));
        fake.carried.put("minecraft:oak_planks", 1);
        fake.unobtainable.add("minecraft:torch");
        List<PlannedCell> cells = List.of(PlannedCell.block(new BlockPos(0, 64, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 64, 0), Blocks.TORCH.defaultBlockState(), Set.of()));
        TaskResult result = run(fake, new Blueprint(DIM, new BlockPos(0, 64, 0), cells));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertTrue(fake.placedOrder.isEmpty(), "等的火把没有，木板也没放");
        assertTrue(details(result).problems().stream().anyMatch(group -> group.reason().equals("要等别的格先放好，那些格没放成")));
    }

    @Test
    void 规则说对着下面一格放_点的是再下面那格的顶面() {
        Fake fake = new Fake().ground(63);
        // 像动力辊：落在 y=65，要对着 y=64 那一格放（原版以为放在那里，物品自己抬高一格），点的是地面的顶面。
        fake.rules.add(new Rule(Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, new BlockPos(0, -1, 0), List.of()));
        fake.carried.put("minecraft:oak_planks", 1);
        TaskResult result = run(fake, wall(Blocks.OAK_PLANKS.defaultBlockState(), new BlockPos(0, 65, 0)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(new BlockPos(0, 63, 0), fake.clicks.getFirst().clicked());
        assertEquals(Direction.UP, fake.clicks.getFirst().face());
        assertEquals(AIR, fake.state(new BlockPos(0, 64, 0)), "对着放的那一格还空着");
    }

    @Test
    void 对着放的那一格被占着_记做不了不出手() {
        Fake fake = new Fake().ground(64);
        fake.rules.add(new Rule(Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, new BlockPos(0, -1, 0), List.of()));
        fake.carried.put("minecraft:oak_planks", 1);
        TaskResult result = run(fake, wall(Blocks.OAK_PLANKS.defaultBlockState(), new BlockPos(0, 65, 0)));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertTrue(fake.clicks.isEmpty());
        assertEquals(1, details(result).cells().get("impossible"));
    }
}
