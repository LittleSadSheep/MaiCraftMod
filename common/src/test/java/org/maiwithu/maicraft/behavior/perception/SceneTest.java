// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景的整理与视图：方位说法、观察编号、失效交代、写进世界记忆、速写按方位成行且列表不截断。 */
class SceneTest {

    private static final Instant NOW = Instant.ofEpochMilli(1000);

    private RecordingMemory memory;
    private Scene scene;

    @BeforeEach
    void freshScene() {
        memory = new RecordingMemory();
        scene = new Scene(memory, new FacilityKinds(FakeBlockTags.vanilla()));
        // 角色站在原点，脸朝北（视角角 180）。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));
    }

    @Test
    void entitiesGetDirectionsIdsAndStableNumbering() {
        scene.updateEntities(10, List.of(
                new EntitySight.Observation(1, "minecraft:zombie", null,
                        WorldPosition.here(0, 65, -12), true, true, true, Map.of()),
                new EntitySight.Observation(2, "minecraft:sheep", null,
                        WorldPosition.here(9, 64, 0), true, false, false,
                        Map.of("color", "white"))));
        List<SceneEntity> entities = scene.entities();
        assertEquals(2, entities.size());
        SceneEntity zombie = entities.get(0);
        assertEquals("e1", zombie.id());
        assertEquals("前方", zombie.direction());
        assertEquals("北", zombie.compass());
        assertEquals(12, zombie.distance());
        assertEquals(1, zombie.dy());
        assertTrue(zombie.hostile() && zombie.targetingMe());
        // 同一只僵尸再刷新一次，编号不变。
        scene.updateEntities(20, List.of(new EntitySight.Observation(1, "minecraft:zombie", null,
                WorldPosition.here(0, 65, -10), true, true, true, Map.of())));
        assertEquals("e1", scene.entities().getFirst().id());
    }

    @Test
    void expireReportsGoneIdsWithLastDirection() {
        scene.updateEntities(100, List.of(new EntitySight.Observation(5, "minecraft:creeper", null,
                WorldPosition.here(0, 64, -3), true, true, false, Map.of())));
        assertTrue(scene.expire(200).isEmpty());
        List<SeenRegistry.Gone> gone = scene.expire(400);
        assertEquals(1, gone.size());
        assertEquals("e1", gone.getFirst().id());
        assertEquals("前方", gone.getFirst().lastDirection());
        assertTrue(scene.entities().isEmpty(), "失效的编号不再出现在实体视图里");
    }

    @Test
    void soundsKeepDirectionAndNearnessWithoutCoordinates() {
        scene.updateSounds(10, List.of(new SubtitleEar.Event("苦力怕嘶嘶声", WorldPosition.here(2, 64, 2))));
        HeardSound sound = scene.heard().getFirst();
        assertEquals("苦力怕嘶嘶声", sound.kind());
        assertEquals("很近", sound.nearness());
        assertFalse(sound.direction().isEmpty());
    }

    @Test
    void facilitiesAreWrittenToMemoryAsSeen() {
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(-4, 64, 0), "minecraft:chest"),
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(0, 64, 5), "minecraft:crafting_table"),
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(0, 64, 7), "minecraft:white_bed")));
        assertEquals(2, memory.calls.size(), "床只做观察报告，不往世界记忆里写");
        assertEquals("container", memory.calls.get(0).what());
        assertEquals("minecraft:chest", memory.calls.get(0).blockType());
        assertEquals("workstation", memory.calls.get(1).what());
        List<FacilitySighting> facilities = scene.facilities();
        assertTrue(facilities.get(0).id().startsWith("b"));
        assertEquals("左侧", facilities.get(0).direction(), "脸朝北时西边在左侧");
    }

    @Test
    void gridUpdateSpotsForestAndWritesSiteLead() {
        // 网格中心在北边 4 格外，顶部一行三格树：聚成一片树林，落在前方。
        char[][] cells = {
                {'T', 'T', 'T'},
                {'.', '.', '.'},
                {'.', '.', '.'}};
        scene.updateGrid(new OverheadGrid.View(new BlockPos(0, 64, -4), 1, cells), 10, NOW);
        TerrainFeature forest = scene.features().getFirst();
        assertTrue(forest.id().startsWith("f"));
        assertEquals("树林", forest.kind());
        assertEquals("前方", forest.direction());
        assertEquals(1, memory.calls.size());
        assertEquals("site", memory.calls.getFirst().what());
        assertEquals(List.of("木头"), memory.calls.getFirst().roughlyThere());
    }

    @Test
    void summaryListsEachDirectionOnItsOwnLineAndGroupsOnlyHugeCounts() {
        scene.updateEntities(10, List.of(
                new EntitySight.Observation(1, "minecraft:zombie", null,
                        WorldPosition.here(0, 64, -8), true, true, false, Map.of()),
                new EntitySight.Observation(2, "minecraft:sheep", null,
                        WorldPosition.here(8, 64, 0), true, false, false, Map.of())));
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(0, 64, 6), "minecraft:crafting_table")));
        String summary = scene.summary();
        assertTrue(summary.contains("前方：e1（minecraft:zombie，8格）"), summary);
        assertTrue(summary.contains("右侧：e2（minecraft:sheep，8格）"), summary);
        assertTrue(summary.contains("b1（minecraft:crafting_table，6格）"), summary);
        assertFalse(summary.contains("按远近计"), "实体没多到分组阈值时不报总数");
    }

    @Test
    void hugeEntityCountsGroupByDistanceBandsWithoutTruncatingTheList() {
        List<EntitySight.Observation> crowd = new ArrayList<>();
        for (int i = 0; i < Scene.GROUP_THRESHOLD + 10; i++) {
            crowd.add(new EntitySight.Observation(1000 + i, "minecraft:chicken", null,
                    WorldPosition.here(i - 25, 64, 0), true, false, false, Map.of()));
        }
        scene.updateEntities(10, crowd);
        assertEquals(Scene.GROUP_THRESHOLD + 10, scene.entities().size(), "列表永远完整，不悄悄截断");
        assertTrue(scene.summary().contains("按远近计"), "速写里按远近分档报总数");
    }

    @Test
    void environmentIsKeptAsGiven() {
        scene.updateEnvironment(new SceneEnvironment("夜晚", "晴", 4, "minecraft:plains"));
        assertEquals("夜晚", scene.environment().timeText());
        assertEquals("minecraft:plains", scene.environment().biome());
    }
}
