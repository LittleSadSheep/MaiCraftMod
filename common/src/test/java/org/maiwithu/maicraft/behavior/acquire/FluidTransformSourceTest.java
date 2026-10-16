// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 往水里扔料这条途径的报价：配方对不上、附近没静水、还没扫完、身上缺料各怎么报；动手时按报价那条配方找回来。 */
class FluidTransformSourceTest {

    private static final AcquireVia VIA = new AcquireVia("ae2_transform", "往静水里扔料等它变");
    private static final String CHARGED = "ae2:charged_certus_quartz_crystal";
    private static final FluidTransform FLUIX = new FluidTransform("ae2:transform/fluix_crystals",
            List.of(List.of(CHARGED), List.of("minecraft:redstone"), List.of("minecraft:quartz")), 0,
            "ae2:fluix_crystal", 2, List.of("minecraft:water", "minecraft:flowing_water"));
    private static final WorldPosition HERE = new WorldPosition(0, 64, 0, "minecraft:overworld");

    @Test
    void 不在世界里与配方对不上都给不了() {
        Rig rig = new Rig();
        rig.inWorld = false;
        assertInstanceOf(SourceQuote.Unavailable.class, rig.quote("ae2:fluix_crystal", 4));
        rig.inWorld = true;
        SourceQuote.Unavailable none = assertInstanceOf(SourceQuote.Unavailable.class, rig.quote("minecraft:diamond", 1));
        assertTrue(none.reason().contains("配方"), none.reason());
    }

    @Test
    void 附近没有静水给不了_还没扫完先等() {
        Rig rig = new Rig();
        SourceQuote.Unavailable dry = assertInstanceOf(SourceQuote.Unavailable.class, rig.quote("ae2:fluix_crystal", 4));
        assertTrue(dry.reason().contains("静水"), dry.reason());
        rig.complete = false;
        assertInstanceOf(SourceQuote.NotYet.class, rig.quote("ae2:fluix_crystal", 4));
    }

    @Test
    void 有静水就报_距离按最前面那格算_缺料照合成加代价_线索是配方() {
        Rig rig = new Rig();
        rig.pools.add(new BlockPos(3, 64, 4));
        rig.pools.add(new BlockPos(1, 64, 0));
        rig.carried.add(new BackpackStack(CHARGED, 4, 64, false, false, false, false));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, rig.quote("ae2:fluix_crystal", 7));
        assertEquals(7, offer.obtainableCount(), "四份出八个，报要的七个");
        assertEquals(5.0, offer.cost().distanceBlocks(), 1e-9, "排在最前面的那格");
        assertEquals(8 * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM, offer.cost().shortfallBlocks(), 1e-9, "红石、下界石英各缺四件");
        assertEquals(FLUIX.id(), offer.hint());
        assertEquals("ae2_transform", rig.source.via().name());
    }

    @Test
    void 动手时按报价那条配方备料再扔_配方对不上就交回空() {
        Rig rig = new Rig();
        rig.pools.add(new BlockPos(1, 64, 0));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, rig.quote("ae2:fluix_crystal", 2));
        SourceContext context = new SourceContext(HERE, Permissions.DEFAULT);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("ae2:fluix_crystal"), 2, "测试");
        assertTrue(rig.source.begin(request, offer, context).isPresent());
        assertEquals(3, rig.asked.size(), "三样料各要一份");
        SourceQuote.Offer stale = new SourceQuote.Offer(offer.source(), 2, offer.cost(), null, "ae2:transform/other");
        assertTrue(rig.source.begin(request, stale, context).isEmpty());
    }

    /** 离线替身：配方表、现场（在不在世界里、找到哪些水格、扫完没有）、背包与缺东西去拿的记账。 */
    private static final class Rig {
        boolean inWorld = true;
        boolean complete = true;
        final List<BlockPos> pools = new ArrayList<>();
        final List<BackpackStack> carried = new ArrayList<>();
        final List<ItemRequest> asked = new ArrayList<>();
        final FluidTransformSource source;

        Rig() {
            FluidTransforms transforms = new FluidTransforms() {
                @Override public AcquireVia via() {
                    return VIA;
                }

                @Override public List<FluidTransform> recipes(Level level) {
                    return List.of(FLUIX);
                }
            };
            TransformSites sites = new TransformSites() {
                @Override public Optional<List<FluidTransform>> recipes(FluidTransforms table) {
                    return inWorld ? Optional.of(table.recipes(null)) : Optional.empty();
                }

                @Override public Pools pools(BlockPos center, Set<String> fluids, int radius) {
                    return new Pools(pools, complete);
                }
            };
            source = new FluidTransformSource(transforms, sites, services(), new NoDrops(), Optional.empty());
        }

        SourceQuote quote(String itemId, int count) {
            return source.quote(new ItemRequest(WantedItem.ofItem(itemId), count, "测试"),
                    new SourceContext(HERE, Permissions.DEFAULT));
        }

        private PlayerServices services() {
            Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                    name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
            BackpackView backpack = new BackpackView() {
                @Override public List<BackpackStack> stacks() { return carried; }
                @Override public int usedSlots() { return carried.size(); }
                @Override public int totalSlots() { return 36; }
            };
            return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                    itemId -> Optional.empty(), new NoGear(), inner -> inner, (request, permissions) -> {
                        asked.add(request);
                        return new Done();
                    }, nobodyOwns, itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE,
                    backpack);
        }
    }

    /** 一下就做完的动作：备料替身。 */
    private static final class Done implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "备料";
        }
    }

    /** 捡掉落物的替身：报价与建动作用不到它。 */
    private static final class NoDrops implements PicksUpDrops {
        @Override public Set<Integer> nearby() {
            return Set.of();
        }

        @Override public Action pickUpNewSince(Set<Integer> before) {
            return new Done();
        }
    }

    /** 换不了装备的替身。 */
    private static final class NoGear implements GearChanges {
        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            return Optional.empty();
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            return Optional.empty();
        }
    }
}
