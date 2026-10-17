// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/** 机器档案库：按世界与名字存取、拆除标记留住蓝图、读不出的档案按没有对待。 */
class MachineArchivesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private MachineBlueprint oneCell() {
        return new MachineBlueprint(
                List.of(PlannedCell.block(new BlockPos(3, 64, 3), Blocks.FURNACE.defaultBlockState(), Set.of())),
                List.of(new MachineBlueprint.Part(new BlockPos(3, 64, 3), Direction.NORTH, "ae2:terminal")),
                List.of(new MachineBlueprint.Segment("test:belt", List.of(new BlockPos(3, 65, 3)))),
                List.of(new MachineBlueprint.Setting(new BlockPos(3, 64, 3), "side.north", "output:items")),
                List.of(new MachineBlueprint.Process(new BlockPos(3, 64, 3), "test:plate")));
    }

    @Test
    void 存进再读回_蓝图与机器条目原样() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        MachineArchive archive = MachineArchive.fresh("压机", "minecraft:overworld", new BlockPos(3, 64, 3),
                oneCell(), "design-1");
        assertTrue(archives.save(archive));
        MachineArchive read = archives.find("压机").orElseThrow();
        assertEquals("压机", read.name());
        assertEquals("minecraft:overworld", read.dimension());
        assertEquals(new BlockPos(3, 64, 3), read.anchor());
        assertEquals(oneCell(), read.blueprint(), "逐格与四种机器条目读回原样");
        assertEquals("design-1", read.designId());
        assertFalse(read.removed());
    }

    @Test
    void 档案按世界分开_换世界读不到() {
        DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
        MachineArchives first = new MachineArchives(documents, "world1");
        MachineArchives second = new MachineArchives(documents, "world2");
        assertTrue(first.save(MachineArchive.fresh("压机", "minecraft:overworld", BlockPos.ZERO, oneCell(), null)));
        assertTrue(second.find("压机").isEmpty(), "另一个世界的档案互不可见");
        assertTrue(first.find("压机").isPresent());
    }

    @Test
    void 拆除标记与合并结果都留得住() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        MachineArchive archive = MachineArchive.fresh("压机", "minecraft:overworld", BlockPos.ZERO, oneCell(), null);
        assertTrue(archives.save(archive.markRemoved()));
        assertTrue(archives.find("压机").orElseThrow().removed(), "拆除只做标记，蓝图保留");
        MachineBlueprint merged = MachinePatch.merge(oneCell(), new MachineBlueprint(
                List.of(PlannedCell.block(new BlockPos(4, 64, 3), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of()));
        MachineArchive updated = archives.find("压机").orElseThrow().withBlueprint(merged, null, null, null)
                .joinedTo(Map.of("me", "net1"));
        assertTrue(archives.save(updated));
        MachineArchive read = archives.find("压机").orElseThrow();
        assertEquals(2, read.blueprint().cells().size());
        assertEquals(Optional.of("net1"), Optional.ofNullable(read.networks().get("me")));
    }

    @Test
    void 没有这个名字_给空() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        assertTrue(archives.find("没有的机器").isEmpty());
    }
}
