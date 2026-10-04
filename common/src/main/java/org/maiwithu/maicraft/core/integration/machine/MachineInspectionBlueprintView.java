// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprint;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Position;

/** full 从地图导出现状并附登记整机的差异；diff 只读声明目标。原设计用于比较，不能填充现状方块。 */
public final class MachineInspectionBlueprintView {
    private MachineInspectionBlueprintView() {}

    /** 默认按档案足迹扩展局部组件扫描，最多八格；地图导出仍可覆盖更大的登记范围，并单独报告覆盖缺口。 */
    public static int componentRadius(MachineBlueprint saved, BlockPos anchor, int radius, boolean explicitRadius) {
        if (explicitRadius || saved == null || saved.captureMin() == null || saved.captureMax() == null) return radius;
        return (int) Math.min(MachineSurvey.MAX_RADIUS, Math.max(radius, extentRadius(saved, anchor)));
    }

    private static long extentRadius(MachineBlueprint saved, BlockPos anchor) {
        long extent = 0;
        // 保留机器原锚点，只扩展只读扫描半径；不能把观察中心平移成后续施工的新坐标原点。
        for (Position corner : new Position[]{saved.captureMin(), saved.captureMax()}) {
            extent = Math.max(extent, Math.abs((long) corner.x() - anchor.getX()));
            extent = Math.max(extent, Math.abs((long) corner.y() - anchor.getY()));
            extent = Math.max(extent, Math.abs((long) corner.z() - anchor.getZ()));
        }
        return extent;
    }
    /** 先读取登记目标的运行事实与差异，再按模式导出现场；这里不导航、投料或修补与设计不符的方块。 */
    public static JsonObject read(LocalPlayer player, MachineBlueprint saved, BlockPos anchor, int radius,
                                  boolean explicitRadius, String mode, int offset, int limit) {
        var result = new JsonObject(); result.addProperty("inspection_mode",mode);
        if (saved != null) result.add("recorded_machine",saved.summary());
        // 已登记机器按全部声明目标读取运行状态；结构分页和周边方块展示不能缩小这份观察范围。
        MachineBlueprintDiff completeMachine = null;
        if (saved != null) {
            try {
                completeMachine = ClientMachineCatalog.comparison(saved);
                result.add("operating_state", completeMachine.operatingState(player.level(), saved.dimension()));
                result.add("native_component_offsets", completeMachine.targetOffsets());
                // full 也同时给整机差异；运行状态与结构变化是一次决策所需的并列事实。
                if (mode.equals("full")) result.add("blueprint_diff",
                        completeMachine.page(player.level(), saved.dimension(), 0, Integer.MAX_VALUE));
            } catch (RuntimeException | LinkageError unavailable) {
                // 旧档案暂时无法展开时仍交付可读的现场，并明确整机范围未知，不能中断整个只读任务。
                result.addProperty("recorded_targets_unavailable", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage());
            }
        }
        if (mode.equals("full")) {
            boolean recordedBounds = saved != null && saved.captureMin() != null && !explicitRadius;
            // 显式半径只替换现状布局和局部勘测范围；上面的整机差异与运行事实仍使用全部登记目标。
            // 超出单次扫描上限时明确暴露范围缺口，局部 native_processes 为空不能伪装成整机没有工艺。
            result.addProperty("native_component_scan_radius", radius);
            if (saved != null && saved.captureMin() != null && saved.captureMax() != null)
                result.addProperty("native_component_bounds_covered", extentRadius(saved, anchor) <= radius);
            BlockPos minimum = recordedBounds ? block(saved.captureMin()) : anchor.offset(-radius,-radius,-radius);
            BlockPos maximum = recordedBounds ? block(saved.captureMax()) : anchor.offset(radius,radius,radius);
            String dimension = saved == null ? player.level().dimension().location().toString() : saved.dimension();
            // 默认从零开始时一次交付完整布局；只有非零偏移才按 limit 读局部页，每次都重新读当前地图。
            var current = MachineWorldBlueprint.page(player.level(),dimension,anchor,minimum,maximum,offset,
                    offset == 0 ? Integer.MAX_VALUE : limit);
            current.addProperty("bounds_source",recordedBounds ? "recorded_machine_extent" : "survey_radius");
            result.add("as_built_blueprint",current);
        } else {
            var diff = new JsonObject();
            if (saved == null) { diff.addProperty("available",false); diff.addProperty("reason","no_recorded_design_blueprint"); }
            else {
                try {
                    if (completeMachine == null) throw new IllegalArgumentException("recorded_targets_unavailable");
                    // diff 始终遍历整台机器，兼容参数 offset/limit 在此不缩小范围，避免漏掉旧部件的联动变化。
                    diff = completeMachine.page(player.level(),saved.dimension(),0,Integer.MAX_VALUE);
                    diff.addProperty("reference_blueprint_fingerprint",saved.fingerprint());
                } catch (RuntimeException | LinkageError unavailable) {
                    diff.addProperty("available",false); diff.addProperty("reason",result.has("recorded_targets_unavailable")
                            ? result.get("recorded_targets_unavailable").getAsString() : unavailable.getMessage());
                }
            }
            result.add("blueprint_diff",diff);
        }
        return result;
    }
    private static BlockPos block(Position at) { return new BlockPos(at.x(),at.y(),at.z()); }
}
