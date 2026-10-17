// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingRecords;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.BuildingLayout;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.RecordsConstruction;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 盖完、改完一栋之后的记账：整栋蓝图对现场比一遍，连同结论存进建筑档案，再把"这一栋在哪、叫什么"记进世界记忆；
 * 改建时再算一次这一栋的布局一起交出去。施工做完、做了一部分、卡住、被取消都记，档案里是整份蓝图，
 * 最近一次比对如实写还差几格，不冒称建成。档案存不进去只在结果里写一句，不改判施工。
 */
final class BuildingRecorder implements RecordsConstruction {

    private final BuildingRecords records;
    private final BuildingArchive planned;
    private final boolean renovation;

    /**
     * @param records    档案库、世界记忆、设施认法
     * @param planned    要存的档案：名字、锚点、朝向、图纸版本、整栋蓝图（比对结论收场时补上）
     * @param renovation 是不是改已有的一栋：改建才在结果里带布局
     */
    BuildingRecorder(BuildingRecords records, BuildingArchive planned, boolean renovation) {
        this.records = Objects.requireNonNull(records, "records");
        this.planned = Objects.requireNonNull(planned, "planned");
        this.renovation = renovation;
    }

    @Override public ResultDetails record(ConstructionDetails details, ReadsBlocks world) {
        Blueprint laid = planned.laid();
        BlueprintCheck.Report check = BlueprintCheck.report(laid, world);
        BuildingArchive archive = planned.with(planned.cells(), planned.designId(), Instant.now(), conclusion(check));
        boolean saved = records.archives().save(archive);
        if (saved) records.remember().accept(archive);
        BlockPos anchor = archive.anchor();
        BuildDetails.Archive note = new BuildDetails.Archive(archive.name(),
                new WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(), archive.dimension()), archive.rotation(),
                archive.designId(), saved ? null : "建筑档案没存进去（存储出错或档案太大），这次施工照常算；之后按名字改这一栋会找不到");
        return BuildDetails.of(details, note, check, renovation ? layoutOf(laid, world) : null);
    }

    /** 整栋比对的一句结论：存进档案，之后看档案就知道上次比到哪。 */
    static String conclusion(BlueprintCheck.Report check) {
        if (!check.complete()) return "有格没加载，没下整栋一致的结论；没对上的 " + check.differences().size() + " 格里含没加载的";
        if (check.allMatch()) return "整栋都对上了";
        return check.differences().size() + " 格没对上";
    }

    // 这一栋的布局：范围往外多看一圈，门两侧、墙外那一格才看得到。
    private BuildingLayout layoutOf(Blueprint laid, ReadsBlocks world) {
        Blueprint.Bounds bounds = laid.bounds();
        return BuildingLayout.of(bounds.min().offset(-1, -1, -1), bounds.max().offset(1, 1, 1), world::state, world::loaded,
                records.facility());
    }

    /** 落到锚点的蓝图换回相对锚点的偏移：档案里存偏移，锚点单独记。 */
    static List<PlannedCell> relative(Blueprint laid) {
        BlockPos anchor = laid.anchor();
        return laid.cells().stream()
                .map(cell -> new PlannedCell(cell.pos().subtract(anchor), cell.kind(), cell.state(), cell.item(),
                        cell.required(), cell.placedBy()))
                .toList();
    }
}
