// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprint;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Position;

/** full 读取地图现状；diff 才使用原设计作参照。档案在 full 模式中只提供身份、位置和读取范围。 */
public final class MachineInspectionBlueprintView {
    private MachineInspectionBlueprintView() {}
    public static JsonObject read(LocalPlayer player, MachineBlueprint saved, BlockPos anchor, int radius,
                                  boolean explicitRadius, String mode, int offset, int limit) {
        var result = new JsonObject(); result.addProperty("inspection_mode",mode);
        if (saved != null) result.add("recorded_machine",saved.summary());
        if (mode.equals("full")) {
            boolean recordedBounds = saved != null && saved.captureMin() != null && !explicitRadius;
            BlockPos minimum = recordedBounds ? block(saved.captureMin()) : anchor.offset(-radius,-radius,-radius);
            BlockPos maximum = recordedBounds ? block(saved.captureMax()) : anchor.offset(radius,radius,radius);
            String dimension = saved == null ? player.level().dimension().location().toString() : saved.dimension();
            var current = MachineWorldBlueprint.page(player.level(),dimension,anchor,minimum,maximum,offset,limit);
            current.addProperty("bounds_source",recordedBounds ? "recorded_machine_extent" : "survey_radius");
            result.add("as_built_blueprint",current);
        } else {
            var diff = new JsonObject();
            if (saved == null) { diff.addProperty("available",false); diff.addProperty("reason","no_recorded_design_blueprint"); }
            else {
                try {
                    diff = new MachineBlueprintDiff(ClientMachineCatalog.blueprintPlan(saved)).page(player.level(),saved.dimension(),offset,limit);
                    diff.addProperty("reference_blueprint_fingerprint",saved.fingerprint());
                } catch (RuntimeException | LinkageError unavailable) {
                    diff.addProperty("available",false); diff.addProperty("reason","recorded_design_unavailable:"+unavailable.getClass().getSimpleName());
                }
            }
            result.add("blueprint_diff",diff);
        }
        return result;
    }
    private static BlockPos block(Position at) { return new BlockPos(at.x(),at.y(),at.z()); }
}
