// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
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
 * 采掘走不到跟前、挖不下来的格记一阵：这一阵里先挑别的树、别的矿，过了这一阵再算回来；
 * 一次动手只坏在这几格时告诉引擎换别处接着拿，备工具没备来这类失败照旧按整个来源走不通算。
 */
class MiningFailedSpotsTest {

    private static final SourceContext CONTEXT = new SourceContext(WorldPosition.here(16, 65, 22), Permissions.DEFAULT);
    private static final WantedItem LOG = WantedItem.ofItem("minecraft:spruce_log");
    /** 坡上那棵树顶上够不着的原木，和另一棵够得着的树。 */
    private static final BlockPos HIGH_LOG = new BlockPos(13, 70, 24);
    private static final BlockPos OTHER_TREE = new BlockPos(20, 65, 20);

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private long now = 1_000_000L;

    /** 替身：一批收不下来的格——每格都走不到跟前，逐格报给来源，然后以问题失败。 */
    private static final class Unreachable implements CollectsBlocks {
        final List<List<BlockPos>> batches = new ArrayList<>();

        @Override public Optional<Action> collect(BlockPos cell, Permissions permissions) {
            return collectBatch(List.of(cell), permissions, null, given -> {});
        }

        @Override public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions,
                Predicate<BlockPos> chainable, Consumer<BlockPos> givenUp) {
            batches.add(List.copyOf(cells));
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    cells.forEach(givenUp);
                    return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                            "走不到 " + cells.getFirst().toShortString() + " 跟前：没有能靠近目标的站位"));
                }
                @Override public String describe() {
                    return "收 " + cells.size() + " 格";
                }
            });
        }
    }

    /** 替身：看得见的原木固定这几格。 */
    private record Logs(List<MinableSpot> spots) implements ScansMinables {
        @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center, int radiusBlocks) {
            return spots;
        }
        @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) {
            return true;
        }
        @Override public boolean scanComplete() {
            return true;
        }
        @Override public boolean buriedUnderfoot(WantedItem wanted) {
            return false;
        }
        @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
            return true;
        }
    }

    private static MinableSpot log(BlockPos pos) {
        return new MinableSpot(pos, "minecraft:spruce_log", "minecraft:spruce_log");
    }

    private MiningSource source(ScansMinables minables, CollectsBlocks collects, ItemNeeds needs) {
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new MiningSource(minables, collects, new NoStairs(), new NoTools(), check, backpack, offhand, needs,
                () -> now);
    }

    @Test
    void 一棵树够不着_只划掉那几格_下次先挑另一棵() {
        Unreachable collects = new Unreachable();
        MiningSource source = source(new Logs(List.of(log(HIGH_LOG), log(OTHER_TREE))), collects, new NoNeeds());
        ItemRequest request = new ItemRequest(LOG, 1, "木头");

        Action first = beginWith(source, request);
        ActionStatus failed = first.tick(null);
        assertInstanceOf(ActionStatus.Failed.class, failed);
        assertTrue(first instanceof FailedOnlyAtSpots spots && spots.onlyThoseSpotsFailed(),
                "只坏在够不着的那一格，引擎换别处接着拿");
        assertEquals(List.of(HIGH_LOG), collects.batches.getFirst());

        // 再问价、再动手：够不着的那格这一阵不挑，去砍另一棵。
        SourceQuote again = source.quote(request, CONTEXT);
        assertEquals(1, assertInstanceOf(SourceQuote.Offer.class, again).obtainableCount());
        beginWith(source, request);
        assertEquals(List.of(OTHER_TREE), collects.batches.get(1));
    }

    @Test
    void 过了这一阵_够不着的格重新算回候选() {
        Unreachable collects = new Unreachable();
        MiningSource source = source(new Logs(List.of(log(HIGH_LOG))), collects, new NoNeeds());
        ItemRequest request = new ItemRequest(LOG, 1, "木头");
        beginWith(source, request).tick(null);

        SourceQuote soon = source.quote(request, CONTEXT);
        String reason = assertInstanceOf(SourceQuote.Unavailable.class, soon).reason();
        assertTrue(reason.contains("刚试过都走不到跟前或挖不下来"), reason);

        now += FailedSpots.REMEMBER_MILLIS;
        assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT), "五分钟后现场可能变了，再算回来");
    }

    @Test
    void 备工具没备来_不算只坏在几格() {
        // 挖煤要铁镐，身上没有、也弄不来：这不是哪一格的事，整个来源这次走不通。
        Unreachable collects = new Unreachable();
        MinableSpot coal = new MinableSpot(new BlockPos(18, 64, 22), "minecraft:coal_ore", "minecraft:coal");
        MiningSource source = source(new Logs(List.of(coal)), collects, new FailingNeeds());
        Action mining = beginWith(source, new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "燃料"));
        assertInstanceOf(ActionStatus.Failed.class, mining.tick(null));
        assertFalse(mining instanceof FailedOnlyAtSpots spots && spots.onlyThoseSpotsFailed());
    }

    private Action beginWith(MiningSource source, ItemRequest request) {
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));
        return source.begin(request, offer, CONTEXT).orElseThrow();
    }

    /** 替身：不用往下挖楼梯。 */
    private static final class NoStairs implements DigsStairsDown {
        @Override public Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions) {
            throw new AssertionError("看得见原木时不该挖楼梯");
        }
    }

    /** 替身：原木徒手能砍，煤矿要铁镐。 */
    private static final class NoTools implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockType) {
            return blockType.endsWith("_ore") ? Optional.of("minecraft:iron_pickaxe") : Optional.empty();
        }
        @Override public boolean sufficient(String toolItemId, String blockType) {
            return toolItemId.equals("minecraft:iron_pickaxe");
        }
    }

    /** 替身：不该来要东西。 */
    private static final class NoNeeds implements ItemNeeds {
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            throw new AssertionError("砍原木不用备东西：" + request.wanted().describe());
        }
    }

    /** 替身：要什么都弄不来。 */
    private static final class FailingNeeds implements ItemNeeds {
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "弄不来" + request.wanted().describe()));
                }
                @Override public String describe() {
                    return "备" + request.wanted().describe();
                }
            };
        }
    }
}
