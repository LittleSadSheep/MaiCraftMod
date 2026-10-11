// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 命中的一半床折成要点那一格的离线场景：世界里的床只要被扫到，就要给出能走过去点的格子——
 * 复现 211：命令只放了半张床（床头格不是床）时，以前被当作"半张床"悄悄丢掉，
 * 选床于是当作附近没有床，退化成现做一张。
 */
class BedCandidateTest {

    private static final BlockPos HIT = new BlockPos(127, 177, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState half(BedPart part, Direction facing) {
        return Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, part)
                .setValue(BedBlock.FACING, facing);
    }

    @Test
    void headHitAnchorsAtTheHeadEvenWithoutTheFoot() {
        // 命中床头：床头就是要点的那格；原版点床头就发起入睡，不要求床尾也在。
        BlockPos cell = BedCandidate.clickCell(HIT, half(BedPart.HEAD, Direction.NORTH),
                Blocks.AIR.defaultBlockState());
        assertEquals(HIT, cell);
    }

    @Test
    void footHitFoldsToTheHeadWhenTheHeadIsThere() {
        // 命中床尾、床头在：折到床头，同一张床的两个半格只报一张。
        BlockPos cell = BedCandidate.clickCell(HIT, half(BedPart.FOOT, Direction.NORTH),
                half(BedPart.HEAD, Direction.NORTH));
        assertEquals(HIT.relative(Direction.NORTH), cell);
    }

    @Test
    void footHitWithoutAHeadIsStillAWorldBed() {
        // 复现 211：命令 /setblock 放的床只有床尾那一格，床头格是空气。
        // 这张床必须照报（锚在床尾上），不能再当作"床头没同步好"丢掉——
        // 丢掉之后选床当作没有床，任务掉进现做床的分支，世界有床却从不走过去。
        BlockPos cell = BedCandidate.clickCell(HIT, half(BedPart.FOOT, Direction.NORTH),
                Blocks.AIR.defaultBlockState());
        assertEquals(HIT, cell);

        // 床头格被换了别的方块也一样：命中的是床，能不能睡让原版点了再结算。
        BlockPos walled = BedCandidate.clickCell(HIT, half(BedPart.FOOT, Direction.EAST),
                Blocks.STONE.defaultBlockState());
        assertEquals(HIT, walled);
    }
}
