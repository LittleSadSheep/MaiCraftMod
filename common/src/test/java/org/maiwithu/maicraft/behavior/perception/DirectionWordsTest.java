// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 方位说法的词面与换算：相对朝向、东南西北、远近档位、高低差，解说与视图都从这里取词。 */
class DirectionWordsTest {

    @Test
    void relativeWordsFaceTheWayTheCharacterLooks() {
        // 脸朝北（视角角 180），东在右手边，南在身后。
        assertEquals("前方", DirectionWords.relative(0, -5, 180f));
        assertEquals("右侧", DirectionWords.relative(6, 0, 180f));
        assertEquals("左侧", DirectionWords.relative(-6, 0, 180f));
        assertEquals("后方", DirectionWords.relative(0, 5, 180f));
        assertEquals("右前方", DirectionWords.relative(3, -3, 180f));
        assertEquals("左后方", DirectionWords.relative(-3, 3, 180f));
    }

    @Test
    void zeroYawFacesSouthByGameConvention() {
        // 视角角 0 朝南：北就成了身后，西在右手边。
        assertEquals("前方", DirectionWords.relative(0, 4, 0f));
        assertEquals("后方", DirectionWords.relative(0, -4, 0f));
        assertEquals("右侧", DirectionWords.relative(-4, 0, 0f));
    }

    @Test
    void compassWordsFollowWorldAxes() {
        assertEquals("北", DirectionWords.compassOf(0, -1));
        assertEquals("东", DirectionWords.compassOf(1, 0));
        assertEquals("南", DirectionWords.compassOf(0, 1));
        assertEquals("西北", DirectionWords.compassOf(-1, -1));
    }

    @Test
    void facingWordComesFromYaw() {
        // 与 compassOf 同一套八扇区：视角角 180 是北，0 是南。
        String facing = DirectionWords.compassOf(
                -Math.sin(Math.toRadians(180f)), Math.cos(Math.toRadians(180f)));
        assertEquals("北", facing);
    }

    @Test
    void distanceBandsCoverNearToFar() {
        assertEquals("很近", DirectionWords.distanceWord(2));
        assertEquals("近处", DirectionWords.distanceWord(10));
        assertEquals("有点远", DirectionWords.distanceWord(30));
        assertEquals("很远", DirectionWords.distanceWord(120));
    }

    @Test
    void heightDifferenceCountsBlocks() {
        assertEquals("同高", DirectionWords.heightWord(0));
        assertEquals("高3格", DirectionWords.heightWord(3));
        assertEquals("低2格", DirectionWords.heightWord(-2));
    }
}
