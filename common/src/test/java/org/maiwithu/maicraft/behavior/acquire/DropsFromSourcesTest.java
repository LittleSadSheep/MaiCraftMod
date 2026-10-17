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
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 掉落线索接进来源：给了线索，采掘照它去挖看得见的模组矿、狩猎照它去打模组生物；没给线索时照旧只认原版对照，
 * 并点出可以用 drops_from 告诉它；挖了掉的不是要的东西，引擎如实写进包的是什么。
 */
class DropsFromSourcesTest {

    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
    private static final WantedItem RAW_ZINC = WantedItem.ofItem("create:raw_zinc");
    private static final DropsFrom ZINC_ORE = new DropsFrom(List.of("create:zinc_ore"), List.of(), List.of());
    private static final BlockPos ZINC_AT = new BlockPos(3, 64, 0);

    /** 野外的、谁的都不是。 */
    private static final ReadsCreatureSituation WILD = uuid -> Optional.of(
            new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false));
    private static final PermissionCheck PERMISSION = new PermissionCheck(
            new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                    name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"), WILD);

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();

    /**
     * 替身：眼前露着一格锌矿。客户端读不到它掉什么，原版对照里也没有，只有线索点名了它才算会掉要的东西——
     * 和读端的并法一样：线索点名的方块并进原版对照。
     */
    private record ZincInSight() implements ScansMinables {
        @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center,
                int radiusBlocks) {
            return dropsFrom.blocks().contains("create:zinc_ore")
                    ? List.of(new MinableSpot(ZINC_AT, "create:zinc_ore", wanted.specifier())) : List.of();
        }
        @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) {
            return !dropsFrom.blocks().isEmpty();
        }
        @Override public boolean scanComplete() {
            return true;
        }
        @Override public boolean buriedUnderfoot(WantedItem wanted) {
            return false;
        }
        @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
            return dropsFrom.blocks().contains(blockTypeId) || blockTypeId.equals("minecraft:iron_ore");
        }
        // 粗铁是铁矿掉的、知道铁矿在哪几层：附近看不见时长程挖矿。
        @Override public Optional<String> depositOre(WantedItem wanted) {
            return "minecraft:raw_iron".equals(wanted.itemId()) ? Optional.of("minecraft:iron_ore") : Optional.empty();
        }
    }

    /** 替身：挖一批记一批，当场做完。 */
    private static final class Digs implements CollectsBlocks {
        final List<BlockPos> dug = new ArrayList<>();

        @Override public Optional<Action> collect(BlockPos cell, Permissions permissions) {
            dug.add(cell);
            return Optional.of(done("挖 " + cell.toShortString()));
        }
    }

    /** 替身：长程挖矿记下通道认哪些方块算挖到了。 */
    private static final class Stairs implements DigsStairsDown {
        Tunnel tunnel;

        @Override public Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions) {
            return done("往下挖楼梯");
        }

        @Override public Optional<Action> tunnelToward(Tunnel tunnel, Permissions permissions) {
            this.tunnel = tunnel;
            return Optional.of(done("长程挖矿"));
        }
    }

    /** 替身：挖什么都不用工具，备料一概不用去弄。 */
    private static final class NoTools implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockType) {
            return Optional.empty();
        }
        @Override public boolean sufficient(String toolItemId, String blockType) {
            return true;
        }
    }

    private static Action done(String what) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }
            @Override public String describe() {
                return what;
            }
        };
    }

    private MiningSource mining(Digs digs, Stairs stairs) {
        ItemNeeds needs = (request, permissions) -> done("备" + request.wanted().describe());
        return new MiningSource(new ZincInSight(), digs, stairs, new NoTools(), PERMISSION, backpack, offhand, needs);
    }

    private static void runAll(Action action) {
        for (int tick = 0; tick < 50; tick++) {
            if (!(action.tick(new StubTick(tick)) instanceof ActionStatus.Running)) return;
        }
    }

    @Test
    void 给了线索_看得见的模组矿照挖() {
        Digs digs = new Digs();
        MiningSource source = mining(digs, new Stairs());
        ItemRequest request = new ItemRequest(RAW_ZINC, 1, "按需要拿取", List.of(), ZINC_ORE);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));
        assertEquals(1, offer.obtainableCount());
        runAll(source.begin(request, offer, CONTEXT).orElseThrow());
        assertEquals(List.of(ZINC_AT), digs.dug, "走过去把那格锌矿挖了");
    }

    @Test
    void 没给线索_模组矿照旧认不出_点出可以用_drops_from() {
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                mining(new Digs(), new Stairs()).quote(new ItemRequest(RAW_ZINC, 1, "按需要拿取"), CONTEXT));
        assertTrue(unavailable.reason().contains("没有方块直接掉出create:raw_zinc"), unavailable.reason());
        assertTrue(unavailable.reason().contains("drops_from"), unavailable.reason());
    }

    @Test
    void 长程挖矿的通道穿过线索点名的方块也算挖到了() {
        Stairs stairs = new Stairs();
        DropsFrom variant = new DropsFrom(List.of("mymod:dense_iron_ore"), List.of(), List.of());
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 2, "按需要拿取", List.of(),
                variant);
        MiningSource source = mining(new Digs(), stairs);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));
        source.begin(request, offer, CONTEXT).orElseThrow();
        assertTrue(stairs.tunnel.yields().test("mymod:dense_iron_ore"));
        assertTrue(stairs.tunnel.yields().test("minecraft:iron_ore"), "原版对照照旧算");
    }

    @Test
    void 给了线索_模组生物也进狩猎候选() {
        List<List<String>> asked = new ArrayList<>();
        ScansCreatures scans = (types, center, radius) -> {
            asked.add(types);
            return types.contains("mymod:boar") ? List.of(new ScansCreatures.CreatureSpot(7, new UUID(0, 7),
                    "mymod:boar", WorldPosition.here(5, 64, 0), true, null, false)) : List.of();
        };
        HuntSource hunt = new HuntSource(scans, WILD, PERMISSION, new FakeTags(), new NoHunts());
        ItemRequest withClue = new ItemRequest(WantedItem.ofItem("mymod:boar_meat"), 1, "按需要拿取", List.of(),
                new DropsFrom(List.of(), List.of(), List.of("mymod:boar")));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, hunt.quote(withClue, CONTEXT));
        assertEquals(1, offer.obtainableCount());
        assertEquals(List.of("mymod:boar"), asked.getFirst());

        // 没给线索：原版对照里没有哪种生物掉它，照旧说打不出来，并点出 drops_from。
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                hunt.quote(new ItemRequest(WantedItem.ofItem("mymod:boar_meat"), 1, "按需要拿取"), CONTEXT));
        assertTrue(unavailable.reason().contains("drops_from"), unavailable.reason());
    }

    /** 狩猎执行的替身：不真的打。 */
    private static final class NoHunts implements Hunts {
        @Override public Optional<Action> hunt(List<ScansCreatures.CreatureSpot> candidates, ItemRequest request,
                Permissions permissions) {
            return Optional.of(done("狩猎替身"));
        }
        @Override public Optional<Action> searchAndHunt(ItemRequest request, SearchPlan plan, Permissions permissions) {
            return Optional.of(done("出去找替身"));
        }
    }

    @Test
    void 挖了掉的不是要的东西_如实写进包的是什么() {
        // LLM 说锌矿掉锌锭（其实掉粗锌）：照挖，挖完背包里多的是粗锌，这一趟一件要的都没拿到，如实写出来。
        ItemSource oreDropsRawZinc = new ItemSource() {
            @Override public String describe() {
                return "采掘";
            }
            @Override public AcquireVia via() {
                return AcquireVia.MINE;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("采掘", request.count(), new AcquisitionCost(3, 1), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        backpack.add("create:raw_zinc", 2);
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "挖锌矿";
                    }
                });
            }
        };
        ItemAcquisition engine = new ItemAcquisition(List.of(oreDropsRawZinc), backpack, offhand, new FakeTags(),
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action run = engine.need(new ItemRequest(WantedItem.ofItem("create:zinc_ingot"), 2, "按需要拿取", List.of(),
                ZINC_ORE), Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 200 && status instanceof ActionStatus.Running; tick++) {
            status = run.tick(new StubTick(tick));
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(failed.problem().message().contains(
                "采掘（做完了，但一件都没拿到；进包的是 create:raw_zinc 2 个，不是要的create:zinc_ingot）"),
                failed.problem().message());
    }
}
