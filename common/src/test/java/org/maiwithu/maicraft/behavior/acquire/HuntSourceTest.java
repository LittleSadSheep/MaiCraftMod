// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
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
 * 狩猎的来源：类型对得上才候选，羊毛只挑有毛、颜色对得上的羊；保护与许可逐只判断，
 * 一只都不许动时来源不参与并写明卡在哪项；附近没有候选时给 max_distance 才出去找，没给如实结束。
 */
class HuntSourceTest {

    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
    private static final SourceContext CONTEXT_WITH_DISTANCE = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT, null, 96.0);

    /** 野外的、谁的都不是。 */
    private static final ReadsCreatureSituation WILD = uuid -> Optional.of(
            new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false));

    private static final PermissionCheck PERMISSION = new PermissionCheck(nobodyOwns(), WILD);

    private static Protection nobodyOwns() {
        return new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
    }

    private static ScansCreatures.CreatureSpot sheep(int id, int x, String color, boolean sheared) {
        return new ScansCreatures.CreatureSpot(id, new UUID(0, id), "minecraft:sheep",
                WorldPosition.here(x, 64, 0), true, color, sheared);
    }

    /** 狩猎执行的替身：把递来的候选记下来，不真的打。 */
    private static final class FakeHunts implements Hunts {
        final List<Object> handed = new ArrayList<>();

        @Override public Optional<Action> hunt(List<ScansCreatures.CreatureSpot> candidates, ItemRequest request,
                Permissions permissions) {
            candidates.forEach(handed::add);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "狩猎替身"; }
            });
        }

        @Override public Optional<Action> searchAndHunt(ItemRequest request, SearchPlan plan, Permissions permissions) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "出去找替身"; }
            });
        }
    }

    private HuntSource source(ScansCreatures scans) {
        return new HuntSource(scans, WILD, PERMISSION, new FakeTags(), new FakeHunts());
    }

    private static ItemRequest wool() {
        return new ItemRequest(WantedItem.ofItem("minecraft:white_wool"), 2, "按需要拿取");
    }

    @Test
    void 白羊毛挑白羊_剪过毛的与黑羊逐类排除并写明() {
        // 附近只有剪过毛的白羊和没剪的黑羊：都有羊毛的问题，一只都不合手。
        HuntSource source = source((types, center, radius) -> List.of(
                sheep(1, 5, "white", true), sheep(2, 9, "black", false)));
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(wool(), CONTEXT));
        assertTrue(unavailable.reason().contains("剪过毛"), unavailable.reason());
        assertTrue(unavailable.reason().contains("毛色不对"), unavailable.reason());
    }

    @Test
    void 合格的候选按只数报价() {
        HuntSource source = source((types, center, radius) -> List.of(
                sheep(1, 5, "white", false), sheep(2, 9, "white", false)));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(wool(), CONTEXT));
        assertEquals(2, offer.obtainableCount());
        assertTrue(offer.risk().contains("只挑"), offer.risk());
    }

    @Test
    void 有名字的羊是别人的_不打() {
        ReadsCreatureSituation named = uuid -> Optional.of(
                new ReadsCreatureSituation.CreatureSituation(false, false, true, false, false, false));
        HuntSource source = new HuntSource((types, center, radius) -> List.of(sheep(1, 5, "white", false)),
                named, new PermissionCheck(nobodyOwns(), named), new FakeTags(), new FakeHunts());
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(wool(), CONTEXT));
        assertTrue(unavailable.reason().contains("别人的"), unavailable.reason());
    }

    @Test
    void 一只都不许动时来源不参与_写明卡在哪项许可() {
        // kill_animals=none：羊还在，但一只都动不得，来源交许可问题而不是"没有"。
        Permissions none = Permissions.DEFAULT.mergedWith(null, null, null,
                Permissions.AnimalKilling.NONE, null, null);
        SourceContext context = new SourceContext(WorldPosition.here(0, 64, 0), none);
        HuntSource source = source((types, center, radius) -> List.of(sheep(1, 5, "white", false)));
        SourceQuote.NeedsApproval approval = assertInstanceOf(SourceQuote.NeedsApproval.class,
                source.quote(wool(), context));
        assertTrue(approval.problem().message().contains("kill_animals"), approval.problem().message());
    }

    @Test
    void 敌对生物照_fight_判许可() {
        // 苦力怕掉火药：它是不怀好意的敌对生物，主动打它是 fight 的事，kill_animals 不覆盖它。
        FakeTags tags = new FakeTags();
        ItemRequest gunpowder = new ItemRequest(WantedItem.ofItem("minecraft:gunpowder"), 1, "按需要拿取");
        ScansCreatures.CreatureSpot creeper = new ScansCreatures.CreatureSpot(9, new UUID(0, 9),
                "minecraft:creeper", WorldPosition.here(6, 64, 0), true, null, false);
        // 处境替身：9 号是敌对生物。
        ReadsCreatureSituation hostile = uuid -> Optional.of(
                new ReadsCreatureSituation.CreatureSituation(false, uuid.equals(new UUID(0, 9)),
                        false, false, false, false));
        HuntSource source = new HuntSource((types, center, radius) -> List.of(creeper), hostile,
                new PermissionCheck(nobodyOwns(), hostile), tags, new FakeHunts());
        // fight=self_defense：主动打它超出许可，写明是 fight 这一项。
        Permissions selfDefense = Permissions.DEFAULT.mergedWith(null, Permissions.Fight.SELF_DEFENSE,
                null, null, null, null);
        SourceQuote quote = source.quote(gunpowder,
                new SourceContext(WorldPosition.here(0, 64, 0), selfDefense));
        assertTrue(quote instanceof SourceQuote.NeedsApproval approval
                && approval.problem().message().contains("fight"), String.valueOf(quote));
    }

    @Test
    void 附近没有候选_给了_max_distance_才出去找() {
        ScansCreatures empty = (types, center, radius) -> List.of();
        // 没给距离：如实结束，不越界去找。
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source(empty).quote(wool(), CONTEXT));
        assertTrue(unavailable.reason().contains("max_distance"), unavailable.reason());
        // 给了：报"出去找"，线索认的是搜索。
        SourceQuote.Offer search = assertInstanceOf(SourceQuote.Offer.class,
                source(empty).quote(wool(), CONTEXT_WITH_DISTANCE));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, search.obtainableCount());
        assertEquals("search", search.hint());
    }

    @Test
    void 对照没有的物品_打猎直接说没有() {
        HuntSource source = source((types, center, radius) -> List.of());
        ItemRequest bread = new ItemRequest(WantedItem.ofItem("minecraft:bread"), 1, "按需要拿取");
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(bread, CONTEXT));
        assertTrue(unavailable.reason().contains("没有哪种生物"), unavailable.reason());
    }

    @Test
    void 动手时把合格的候选交给执行接缝() {
        List<ScansCreatures.CreatureSpot> scanned = List.of(sheep(7, 4, "white", false));
        List<Object> handed = new ArrayList<>();
        FakeHunts hunts = new FakeHunts();
        HuntSource source = new HuntSource((types, center, radius) -> scanned, WILD, PERMISSION, new FakeTags(), hunts);
        Optional<Action> begun = source.begin(wool(),
                new SourceQuote.Offer("狩猎", 1, new AcquisitionCost(4, 5), "", null), CONTEXT);
        assertTrue(begun.isPresent());
        assertEquals(scanned, hunts.handed);
    }
}
