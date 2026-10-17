// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 收回自己放下的床、工作台、熔炉：按路程报价，走过去挖下来；别人放的、家附近的、归属没问到的、
 * 已经不在原处的都不收；许可不让挖就说要哪项许可。
 */
class ReclaimSourceTest {

    @TempDir Path temp;

    /** 角色在家附近站着；野外那张床在一百多格外。 */
    private static final WorldPosition HERE = WorldPosition.here(20, 63, 26);
    private static final WorldPosition FIELD_BED = WorldPosition.here(-86, 66, 50);
    private static final WantedItem ANY_BED = WantedItem.ofTag("minecraft:beds");

    private final Map<WorldPosition, String> world = new HashMap<>();
    private final Map<WorldPosition, String> owners = new HashMap<>();
    private final Set<WorldPosition> unknownOwnership = new HashSet<>();
    private final Map<String, WorldPosition> places = new HashMap<>();
    private final List<BlockPos> collected = new ArrayList<>();
    private final FakeTags tags = new FakeTags();
    private WorldMemory memory;

    /** 这个世界的记忆：第一次用时建在临时目录里。 */
    private WorldMemory memory() {
        if (memory == null) memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        return memory;
    }

    private ReclaimSource source() {
        tags.put("minecraft:white_bed", "minecraft:beds");
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.ofNullable(owners.get(WorldPosition.here(x, y, z))).map(PlacedBy::new);
            }
            @Override public boolean known(String dimension, int x, int y, int z) {
                return !unknownOwnership.contains(WorldPosition.here(x, y, z));
            }
        };
        Protection protection = new Protection(ownership, () -> List.of(),
                name -> Optional.ofNullable(places.get(name)), GuessesPlayerMade.NOTHING, "self");
        PermissionCheck check = new PermissionCheck(protection, new ReadsCreatureSituation() {
            @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                return Optional.empty();
            }
        });
        CollectsBlocks collects = new CollectsBlocks() {
            @Override public Optional<Action> collect(BlockPos cell, Permissions permissions) {
                collected.add(cell);
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "收 " + cell.toShortString();
                    }
                });
            }
        };
        return new ReclaimSource(memory(), at -> Optional.ofNullable(world.get(at)), protection, check, collects, tags);
    }

    /** 自己放下、睡过一晚的那张床：记在世界记忆里，现场还在，归属记录是自己。 */
    private void placedBed(WorldPosition at, String owner) {
        memory().rememberWorkstationUsed(at, "minecraft:white_bed", Instant.now());
        world.put(at, "minecraft:white_bed");
        owners.put(at, owner);
    }

    private SourceQuote quote(WantedItem wanted, Permissions permissions) {
        return source().quote(new ItemRequest(wanted, 1, "睡觉"), new SourceContext(HERE, permissions));
    }

    @Test
    void 自己放下的床_按路程报价_动手走过去挖下来() {
        placedBed(FIELD_BED, "self");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, Permissions.DEFAULT));
        assertEquals(1, offer.obtainableCount());
        assertTrue(offer.cost().distanceBlocks() > 100 && offer.cost().distanceBlocks() < 112, offer.toString());

        ItemRequest request = new ItemRequest(ANY_BED, 1, "睡觉");
        source().begin(request, offer, new SourceContext(HERE, Permissions.DEFAULT)).orElseThrow();
        assertEquals(List.of(new BlockPos(-86, 66, 50)), collected);
    }

    @Test
    void 点名要白床也收得回() {
        placedBed(FIELD_BED, "self");
        assertInstanceOf(SourceQuote.Offer.class, quote(WantedItem.ofItem("minecraft:white_bed"), Permissions.DEFAULT));
        assertInstanceOf(SourceQuote.Unavailable.class, quote(WantedItem.ofItem("minecraft:red_bed"), Permissions.DEFAULT));
    }

    @Test
    void 别人放的床_不收() {
        placedBed(FIELD_BED, "someone");
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 家附近的床_不收() {
        placedBed(FIELD_BED, "self");
        places.put("家", WorldPosition.here(-84, 66, 52));
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 归属还没问到_先等一等再问() {
        placedBed(FIELD_BED, "self");
        unknownOwnership.add(FIELD_BED);
        assertInstanceOf(SourceQuote.NotYet.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 记得的床已经不在原处_不收() {
        placedBed(FIELD_BED, "self");
        world.remove(FIELD_BED);
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 一格都不许动_说要哪项许可() {
        placedBed(FIELD_BED, "self");
        Permissions none = new Permissions(Permissions.BlockChanges.NONE, Permissions.Fight.HOSTILE_MOBS, false,
                Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of());
        assertInstanceOf(SourceQuote.NeedsApproval.class, quote(ANY_BED, none));
    }

    @Test
    void 工作台与熔炉也收_箱子不收() {
        WorldPosition table = WorldPosition.here(25, 63, 30);
        WorldPosition chest = WorldPosition.here(26, 63, 30);
        memory().rememberWorkstationUsed(table, "minecraft:crafting_table", Instant.now());
        memory().rememberWorkstationUsed(chest, "minecraft:chest", Instant.now());
        world.put(table, "minecraft:crafting_table");
        world.put(chest, "minecraft:chest");
        owners.put(table, "self");
        owners.put(chest, "self");
        assertInstanceOf(SourceQuote.Offer.class,
                quote(WantedItem.ofItem("minecraft:crafting_table"), Permissions.DEFAULT));
        assertInstanceOf(SourceQuote.Unavailable.class, quote(WantedItem.ofItem("minecraft:chest"), Permissions.DEFAULT));
    }
}
