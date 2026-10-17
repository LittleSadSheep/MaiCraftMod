// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;

/**
 * 掉落线索的分拣：# 开头的按方块标签认，其余先查方块再查实体，两边都有的按方块；
 * 写错的一项一句"不存在；你是不是要 …"，一次报全。注册表用替身摆名单。
 */
class DropsFromTest {

    /** 替身：本实例注册了锌矿、石头、TNT 这几种方块，僵尸、TNT 这两种实体，挂着东西的方块标签只有锌矿那个。 */
    private static final ReadsRegisteredIds REGISTERED = new ReadsRegisteredIds() {
        @Override public List<String> ids(IdKind kind) {
            return switch (kind) {
                case BLOCK -> List.of("create:zinc_ore", "minecraft:stone", "minecraft:tnt");
                case ENTITY_TYPE -> List.of("minecraft:tnt", "minecraft:zombie");
                default -> List.of();
            };
        }

        @Override public List<String> tags(IdKind kind) {
            return kind == IdKind.BLOCK ? List.of("c:ores/zinc") : List.of();
        }
    };

    @Test
    void 方块_方块标签_实体各归各的() {
        DropsFrom.Sorted sorted = DropsFrom.sort(
                List.of("create:zinc_ore", "#c:ores/zinc", "minecraft:zombie"), REGISTERED);
        assertTrue(sorted.problems().isEmpty(), sorted.problems().toString());
        assertEquals(List.of("create:zinc_ore"), sorted.dropsFrom().blocks());
        assertEquals(List.of("c:ores/zinc"), sorted.dropsFrom().blockTags());
        assertEquals(List.of("minecraft:zombie"), sorted.dropsFrom().entities());
    }

    @Test
    void 既是方块又是实体的按方块() {
        DropsFrom.Sorted sorted = DropsFrom.sort(List.of("minecraft:tnt"), REGISTERED);
        assertEquals(List.of("minecraft:tnt"), sorted.dropsFrom().blocks());
        assertTrue(sorted.dropsFrom().entities().isEmpty());
    }

    @Test
    void 写错的给相近的名字_一次报全() {
        DropsFrom.Sorted sorted = DropsFrom.sort(
                List.of("create:zinc_or", "minecraft:zombi", "#c:ores/zin"), REGISTERED);
        assertEquals(List.of(
                "方块或实体 create:zinc_or 不存在；你是不是要 create:zinc_ore",
                "方块或实体 minecraft:zombi 不存在；你是不是要 minecraft:zombie",
                "方块标签 #c:ores/zin 不存在；你是不是要 #c:ores/zinc"), sorted.problems());
        assertTrue(sorted.dropsFrom().isEmpty(), "写错的一项都不进线索");
    }
}
