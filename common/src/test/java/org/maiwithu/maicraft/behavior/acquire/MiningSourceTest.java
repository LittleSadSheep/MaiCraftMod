// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /** 替身：固定一批可挖的方块，或声明世界上根本没有方块直接掉它。 */
    private record FakeMinables(List<MinableSpot> spots, boolean anyDrops) implements ScansMinables {
        @Override public List<MinableSpot> minable(WantedItem wanted, WorldPosition center, int radiusBlocks) {
            return spots;
        }
        @Override public boolean anyBlockDrops(WantedItem wanted) {
            return anyDrops;
        }
    }

    /** 替身：挖一格记一格，当场做完；接不上时返回 empty。 */
    private static final class FakeDigs implements DigsBlocks {
        final List<BlockPos> dug = new ArrayList<>();
        private boolean wired = true;

        FakeDigs wired(boolean wired) {
            this.wired = wired;
            return this;
        }

        @Override public Optional<Action> dig(BlockPos target) {
            if (!wired) return Optional.empty();
            dug.add(target);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "挖一格";
                }
            });
        }
    }

    /** 替身许可：铁矿石要铁镐；钻石矿石需要钻石镐（等级不够也判不够格）。 */
    private static final class FakeTools implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockType) {
            return blockType.endsWith("_ore") ? Optional.of("minecraft:iron_pickaxe") : Optional.empty();
        }
        @Override public boolean sufficient(String toolItemId, String blockType) {
            return toolItemId.equals("minecraft:iron_pickaxe");
        }
    }

    private static MinableSpot coalAt(int x, int z) {
        return new MinableSpot(new BlockPos(x, 64, z), "minecraft:coal_ore", "minecraft:coal");
    }

    private MiningSource source(ScansMinables minables, DigsBlocks digs) {
        // 真许可检查点加全空的替身保护：本场景里没有任何受保护的东西，挡路的只有许可档位。
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new MiningSource(minables, digs, new FakeTools(), check, backpack, offhand, needs);
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
}
