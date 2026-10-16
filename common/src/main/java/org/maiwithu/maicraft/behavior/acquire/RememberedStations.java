// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 记得的工作站：按设施种类从世界记忆里找眼下还在的，离角色近的在前。挑哪台工作台、烧炉子正忙时能换哪几台，
 * 都用这一把尺子。眼下看得到已经没了的（被挖走、被炸掉）先从记忆里划掉：不然会走过去点一块空地，
 * 整件合成白白失败，身上明明还有一张工作台也不会放下来用。
 */
final class RememberedStations {

    private final WorldMemory memory;
    /** 认"这台设施已经没了"的接缝；没接上时只信记忆。 */
    private final SetsUpWorkstation placer;
    private final int radiusBlocks;

    RememberedStations(WorldMemory memory, SetsUpWorkstation placer, int radiusBlocks) {
        this.memory = memory;
        this.placer = placer;
        this.radiusBlocks = radiusBlocks;
    }

    /** 离角色最近、眼下还在的那台。 */
    Optional<MemoryRecord> nearest(WorkstationRecipe.Kind kind, WorldPosition characterAt) {
        return standing(kind, characterAt).stream().findFirst();
    }

    /** 烧炉子能换的几台：挑中的那台排第一，其余记得的同种设施按离角色远近跟在后面。 */
    List<WorldPosition> nearestFirst(WorkstationRecipe.Kind kind, WorldPosition chosen, WorldPosition characterAt) {
        List<WorldPosition> stations = new ArrayList<>(List.of(chosen));
        for (MemoryRecord record : standing(kind, characterAt)) {
            if (!MemoryRecord.sameSpot(record.position(), chosen)) stations.add(record.position());
        }
        return stations;
    }

    // 记得的这种设施里眼下还在的，离角色近的在前：世界记忆按记下的先后排，最新记的不一定最近。
    private List<MemoryRecord> standing(WorkstationRecipe.Kind kind, WorldPosition characterAt) {
        String blockType = kind.blockType();
        List<MemoryRecord> remembered = memory.recordsNear(characterAt, radiusBlocks).stream()
                .filter(record -> record.kind() == MemoryKind.WORKSTATION)
                .filter(record -> blockType.equals(record.blockType()))
                .toList();
        List<MemoryRecord> standing = new ArrayList<>();
        for (MemoryRecord record : remembered) {
            if (placer != null && placer.gone(record.position(), blockType)) {
                memory.forget(MemoryKind.WORKSTATION, record.position());
            } else {
                standing.add(record);
            }
        }
        return standing.stream().sorted(Comparator.comparingDouble(record -> distance(record.position(), characterAt)))
                .toList();
    }

    /** 两处之间的直线距离（格）。 */
    static double distance(WorldPosition at, WorldPosition here) {
        double dx = at.x() - here.x();
        double dy = at.y() - here.y();
        double dz = at.z() - here.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
