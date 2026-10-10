// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采掘的来源：加工品明确说挖不出来；矿石按许可筛格子；缺合用的镐先按同一套需求去弄；
 * 挖的动作走挖方块的执行接缝，接缝没接上就交回空由引擎换路。
 */
class MiningSourceTest {

    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final CapturingNeeds needs = new CapturingNeeds();

    /** 替身：记下递归进来的内部需求，动作当场做完。 */
    private static final class CapturingNeeds implements ItemNeeds {
        final List<ItemRequest> asked = new ArrayList<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "备" + request.wanted().describe();
                }
            };
        }
    }

    /** 替身：固定一批可挖的方块，或声明世界上根本没有方块直接掉它；buried 表示要的是埋在脚下的石头掉的；complete 表示扫完了。 */
    private record FakeMinables(List<MinableSpot> spots, boolean anyDrops, boolean buried, boolean complete)
            implements ScansMinables {
        FakeMinables(List<MinableSpot> spots, boolean anyDrops) {
            this(spots, anyDrops, false, true);
        }
        FakeMinables(List<MinableSpot> spots, boolean anyDrops, boolean buried) {
            this(spots, anyDrops, buried, true);
        }
        @Override public boolean scanComplete() {
            return complete;
        }
        @Override public List<MinableSpot> minable(WantedItem wanted, WorldPosition center, int radiusBlocks) {
            return spots;
        }
        @Override public boolean anyBlockDrops(WantedItem wanted) {
            return anyDrops;
        }
        @Override public boolean buriedUnderfoot(WantedItem wanted) {
            return buried;
        }
        @Override public boolean dropsWanted(WantedItem wanted, String blockTypeId) {
            return blockTypeId.equals("minecraft:stone");
        }
    }

    /** 替身：记下要往下挖几格、哪些方块算数，动作当场做完。 */
    private static final class FakeStairs implements DigsStairsDown {
        int wantedCells;
        Predicate<String> yields;

        @Override public Action digDown(Predicate<String> yields, int wantedCells,
                Permissions permissions) {
            this.yields = yields;
            this.wantedCells = wantedCells;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "往下挖楼梯";
                }
            };
        }
    }

    private final FakeStairs stairs = new FakeStairs();

    /** 替身：挖一格记一格，当场做完；接不上时返回 empty。 */
    private static final class FakeDigs implements CollectsBlocks {
        final List<BlockPos> dug = new ArrayList<>();
        private boolean wired = true;

        FakeDigs wired(boolean wired) {
            this.wired = wired;
            return this;
        }

        @Override public Optional<Action> collect(BlockPos target, Permissions permissions) {
            return collectBatch(List.of(target), permissions);
        }

        /** 成批收：一批记一次，格子按先后记进 dug。 */
        final List<List<BlockPos>> batches = new ArrayList<>();

        @Override public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions) {
            if (!wired) return Optional.empty();
            dug.addAll(cells);
            batches.add(List.copyOf(cells));
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "收 " + cells.size() + " 格";
                }
            });
        }
    }

    /** 替身许可：矿石要铁镐，石头要木镐；带对了镐才算够格。 */
    private static final class FakeTools implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockType) {
            if (blockType.equals("minecraft:stone")) return Optional.of("minecraft:wooden_pickaxe");
            return blockType.endsWith("_ore") ? Optional.of("minecraft:iron_pickaxe") : Optional.empty();
        }
        @Override public boolean sufficient(String toolItemId, String blockType) {
            return blockType.equals("minecraft:stone") ? toolItemId.endsWith("_pickaxe")
                    : toolItemId.equals("minecraft:iron_pickaxe");
        }
    }

    private static MinableSpot coalAt(int x, int z) {
        return new MinableSpot(new BlockPos(x, 64, z), "minecraft:coal_ore", "minecraft:coal");
    }

    private MiningSource source(ScansMinables minables, CollectsBlocks digs) {
        // 真许可检查点加全空的替身保护：本场景里没有任何受保护的东西，挡路的只有许可档位。
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new MiningSource(minables, digs, stairs, new FakeTools(), check, backpack, offhand, needs);
    }

    @Test
    void 加工品明确说挖不出来() {
        SourceQuote quote = source(new FakeMinables(List.of(), false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 2, "工具准备"), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("合成或烧炼"));
    }

    @Test
    void 附近有矿_按格子数报价_风险写明掉落看运气() {
        SourceQuote quote = source(new FakeMinables(List.of(coalAt(3, 0), coalAt(6, 0)), true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "火把"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals(2, offer.obtainableCount());
        assertEquals(3.0, offer.cost().distanceBlocks(), 0.001);
        assertTrue(offer.risk().contains("不一定掉一件"));
    }

    @Test
    void 身上没有合用的镐_动手时先备工具再挖() {
        FakeDigs digs = new FakeDigs();
        Action action = source(new FakeMinables(List.of(coalAt(3, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .orElseThrow();
        runAll(action);
        assertEquals(1, needs.asked.size());
        assertEquals("minecraft:iron_pickaxe", needs.asked.getFirst().wanted().specifier());
        assertTrue(needs.asked.getFirst().purpose().contains("工具"));
        assertEquals(List.of(new BlockPos(3, 64, 0)), digs.dug);
    }

    @Test
    void 身上有合用的镐_直接挖() {
        backpack.addGear("minecraft:iron_pickaxe");
        FakeDigs digs = new FakeDigs();
        Action action = source(new FakeMinables(List.of(coalAt(3, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .orElseThrow();
        runAll(action);
        assertTrue(needs.asked.isEmpty());
        assertEquals(1, digs.dug.size());
    }

    @Test
    void 挖的入口没接上_交回空由引擎换路() {
        FakeDigs unwired = new FakeDigs().wired(false);
        assertTrue(source(new FakeMinables(List.of(coalAt(3, 0)), true), unwired)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .isEmpty());
    }

    private static void runAll(Action action) {
        for (int tick = 1; tick <= 100; tick++) {
            if (!(action.tick(new StubTick(tick)) instanceof ActionStatus.Running)) return;
        }
        throw new AssertionError("一百刻还没做完");
    }

    @Test
    void 要几件就挖几格_不把附近的矿挖光() {
        FakeDigs digs = new FakeDigs();
        backpack.add("minecraft:iron_pickaxe", 1);
        source(new FakeMinables(List.of(coalAt(3, 0), coalAt(4, 0), coalAt(5, 0), coalAt(6, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "火把"),
                        new SourceQuote.Offer("采掘", 4, new AcquisitionCost(3, 4), null), CONTEXT)
                .orElseThrow();
        assertEquals(2, digs.dug.size());
    }

    @Test
    void 看不见石头_要圆石时往下挖楼梯报价() {
        // 实机：新世界地表全是土，obtain 圆石只回答"附近看得见的地方没有"。石头谁都知道埋在脚下。
        SourceQuote quote = source(new FakeMinables(List.of(), true, true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals(3, offer.obtainableCount());
        assertTrue(offer.risk().contains("楼梯"), offer.risk());
        assertTrue(offer.risk().contains("minecraft:wooden_pickaxe"), "没带镐要先说会去备：" + offer.risk());
    }

    @Test
    void 看不见煤_照旧说附近没有_不往下挖() {
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), CONTEXT);
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    @Test
    void 不许挖天然方块_往下挖楼梯要许可() {
        SourceContext onlyTemporary = new SourceContext(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.TEMPORARY, null, null, null, null, null));
        SourceQuote quote = source(new FakeMinables(List.of(), true, true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), onlyTemporary);
        SourceQuote.NeedsApproval approval = assertInstanceOf(SourceQuote.NeedsApproval.class, quote);
        assertTrue(approval.problem().message().contains("change_blocks"), approval.problem().message());
    }

    @Test
    void 往下挖楼梯_没镐先备木镐_挖够要的件数() {
        Action action = source(new FakeMinables(List.of(), true, true), new FakeDigs())
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"),
                        new SourceQuote.Offer("采掘", 3, new AcquisitionCost(4, 7), null), CONTEXT)
                .orElseThrow();
        runAll(action);

        assertEquals("minecraft:wooden_pickaxe", needs.asked.getFirst().wanted().itemId());
        assertEquals(3, stairs.wantedCells);
        assertTrue(stairs.yields.test("minecraft:stone"));
        assertTrue(!stairs.yields.test("minecraft:dirt"), "挖开的土不算挖到圆石");
    }

    @Test
    void 附近还没扫完_回答再问_不说没有() {
        // 实机：方块索引分刻建，刚进世界第一问常常只扫了一部分；不能把没扫完当成"附近没有"去挖楼梯或放弃。
        SourceQuote quote = source(new FakeMinables(List.of(), true, true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), CONTEXT);
        assertInstanceOf(SourceQuote.NotYet.class, quote);
    }

    @Test
    void 附近没有的回答写明半径() {
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().startsWith("半径 48 格内"), unavailable.reason());
    }

    @Test
    void 相邻的矿连成一批一次收_远处的不算() {
        // 煤矿 (3,0)(4,0)(5,0) 连着，(20,0) 远远的一格：要 3 件就收连着的那 3 格，一批、一次捡。
        FakeDigs digs = new FakeDigs();
        backpack.add("minecraft:iron_pickaxe", 1);
        source(new FakeMinables(List.of(coalAt(3, 0), coalAt(4, 0), coalAt(20, 0), coalAt(5, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"),
                        new SourceQuote.Offer("采掘", 4, new AcquisitionCost(3, 4), null), CONTEXT)
                .orElseThrow();
        assertEquals(1, digs.batches.size());
        assertEquals(List.of(new BlockPos(3, 64, 0), new BlockPos(4, 64, 0), new BlockPos(5, 64, 0)), digs.batches.getFirst());
    }

    @Test
    void 原木只沿树干竖向成批_旁边的横梁不算() {
        // 砍树：树干 (0,64..66) 连成一批；旁边同高的一根 (1,64) 是横梁，不并进来。
        MinableSpot trunk0 = new MinableSpot(new BlockPos(0, 64, 0), "minecraft:oak_log", "minecraft:oak_log");
        MinableSpot trunk1 = new MinableSpot(new BlockPos(0, 65, 0), "minecraft:oak_log", "minecraft:oak_log");
        MinableSpot trunk2 = new MinableSpot(new BlockPos(0, 66, 0), "minecraft:oak_log", "minecraft:oak_log");
        MinableSpot beam = new MinableSpot(new BlockPos(1, 64, 0), "minecraft:oak_log", "minecraft:oak_log");
        List<MinableSpot> batch = MiningSource.batchOf(List.of(trunk0, beam, trunk1, trunk2), 8);
        assertEquals(List.of(trunk0, trunk1, trunk2), batch);
    }
}
