// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 挑容器：候选的过滤、排序与失败分流。 */
class ContainerChooserTest {
    private final WorldPosition near = new WorldPosition(0, 64, 0, null);

    private ContainerChooser.Candidate candidate(String name, double cost) {
        return new ContainerChooser.Candidate(name, "minecraft:chest", near, null, true, cost,
                false, true, ContainerChooser.Lid.CLEAR, false);
    }

    @Test
    void sortsSameItemFirstThenFreeSpaceThenCost() {
        ContainerChooser.Candidate fullWithItem = new ContainerChooser.Candidate(
                "满的同种箱", "minecraft:chest", near, List.of("minecraft:cobblestone"), false, 2,
                false, true, ContainerChooser.Lid.CLEAR, false);
        ContainerChooser.Candidate emptyNear = candidate("近的空箱", 1);
        ContainerChooser.Candidate farWithItem = new ContainerChooser.Candidate(
                "远的同种箱", "minecraft:chest", near, List.of("minecraft:cobblestone"), true, 5,
                false, true, ContainerChooser.Lid.CLEAR, false);
        var pick = ContainerChooser.choose(List.of(emptyNear, fullWithItem, farWithItem),
                "minecraft:cobblestone", true);
        assertTrue(pick instanceof ContainerChooser.Pick.Chosen);
        var chosen = (ContainerChooser.Pick.Chosen) pick;
        assertEquals(List.of("远的同种箱", "满的同种箱", "近的空箱"),
                chosen.ordered().stream().map(entry -> entry.candidate().name()).toList());
    }

    @Test
    void skipsOtherPeopleChestsAndUnknownLayouts() {
        ContainerChooser.Candidate other = new ContainerChooser.Candidate(
                "别人的箱子", "minecraft:chest", near, null, true, 1,
                true, true, ContainerChooser.Lid.CLEAR, false);
        ContainerChooser.Candidate ae2 = new ContainerChooser.Candidate(
                "ME 终端", "ae2:controller", near, null, true, 1,
                false, false, ContainerChooser.Lid.CLEAR, false);
        ContainerChooser.Candidate mine = candidate("自己的箱子", 3);
        var pick = ContainerChooser.choose(List.of(other, ae2, mine), null, true);
        assertTrue(pick instanceof ContainerChooser.Pick.Chosen);
        var chosen = (ContainerChooser.Pick.Chosen) pick;
        assertEquals(List.of("自己的箱子"), chosen.ordered().stream()
                .map(entry -> entry.candidate().name()).toList());
    }

    @Test
    void onlyOtherChestsMeansApproval() {
        ContainerChooser.Candidate other = new ContainerChooser.Candidate(
                "别人的箱子", "minecraft:chest", near, null, true, 1,
                true, true, ContainerChooser.Lid.CLEAR, false);
        var pick = ContainerChooser.choose(List.of(other), null, true);
        assertTrue(pick instanceof ContainerChooser.Pick.NeedApproval);
        var approval = (ContainerChooser.Pick.NeedApproval) pick;
        assertEquals(1, approval.others().size());
        assertTrue(approval.others().getFirst().contains("别人的箱子"));
    }

    @Test
    void nothingAtAllMeansNotFound() {
        var pick = ContainerChooser.choose(List.of(), null, true);
        assertTrue(pick instanceof ContainerChooser.Pick.NotFound);
        var notFound = (ContainerChooser.Pick.NotFound) pick;
        assertTrue(notFound.searched().contains("没有找到任何容器"));
    }

    @Test
    void naturalLidDigsWhenAllowedElseApproval() {
        ContainerChooser.Candidate leafLid = new ContainerChooser.Candidate(
                "树叶压住的箱子", "minecraft:chest", near, null, true, 1,
                false, true, ContainerChooser.Lid.BLOCKED_BY_NATURAL, false);
        // 许可允许：先挖开再用。
        var allowed = ContainerChooser.choose(List.of(leafLid), null, true);
        assertTrue(allowed instanceof ContainerChooser.Pick.Chosen);
        assertTrue(((ContainerChooser.Pick.Chosen) allowed).ordered().getFirst().digLidFirst());
        // 许可不允许：要问，并写明是哪一格。
        var refused = ContainerChooser.choose(List.of(leafLid), null, false);
        assertTrue(refused instanceof ContainerChooser.Pick.NeedApproval);
        var approval = (ContainerChooser.Pick.NeedApproval) refused;
        assertTrue(approval.blockedLids().getFirst().contains("树叶压住的箱子"));
    }

    @Test
    void catSittingChestIsSkipped() {
        ContainerChooser.Candidate cat = new ContainerChooser.Candidate(
                "有猫的箱子", "minecraft:chest", near, null, true, 1,
                false, true, ContainerChooser.Lid.CLEAR, true);
        var pick = ContainerChooser.choose(List.of(cat), null, true);
        assertTrue(pick instanceof ContainerChooser.Pick.NotFound);
        var notFound = (ContainerChooser.Pick.NotFound) pick;
        assertTrue(notFound.searched().contains("坐着猫"));
    }
}
