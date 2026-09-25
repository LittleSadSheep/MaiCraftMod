// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Position;

/** 保存机器的原地蓝图和最近施工结果；历史建成记录不代表地图上的机器现在仍然完整。 */
public record MachineBlueprint(String id, String label, String dimension, Position anchor, String blueprintJson,
                               String fingerprint, String lastBuildState, long registeredAtMillis, long builtAtMillis,
                               Position captureMin, Position captureMax) {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public MachineBlueprint(String id, String label, String dimension, Position anchor, String blueprintJson,
                            String fingerprint, String lastBuildState, long registeredAtMillis, long builtAtMillis) {
        this(id,label,dimension,anchor,blueprintJson,fingerprint,lastBuildState,registeredAtMillis,builtAtMillis,null,null);
    }
    public MachineBlueprint {
        id = CatalogLimits.text(id, 80, "machine id"); label = CatalogLimits.text(label, 160, "machine label");
        dimension = CatalogLimits.registry(dimension, "machine dimension");
        if (anchor == null || blueprintJson == null || blueprintJson.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("machine_blueprint_archive_size");
        CatalogLimits.jsonDepth(blueprintJson);
        var blueprint = JsonParser.parseString(blueprintJson).getAsJsonObject();
        if (!blueprint.has("blocks") || !blueprint.get("blocks").isJsonArray())
            throw new IllegalArgumentException("machine_blueprint_blocks_missing");
        if (!CatalogLimits.hash(blueprintJson).equals(fingerprint)) throw new IllegalArgumentException("machine_blueprint_fingerprint_mismatch");
        if (!Set.of("planned", "running", "success", "failed", "cancelled", "timeout").contains(lastBuildState))
            throw new IllegalArgumentException("machine_blueprint_build_state");
        CatalogLimits.nonnegative(registeredAtMillis, "machine registration"); CatalogLimits.nonnegative(builtAtMillis, "machine completion");
        if ((captureMin == null) != (captureMax == null) || captureMin != null
                && (captureMin.x() > captureMax.x() || captureMin.y() > captureMax.y() || captureMin.z() > captureMax.z()))
            throw new IllegalArgumentException("machine_capture_bounds_invalid");
    }
    public JsonObject blueprint() { return JsonParser.parseString(blueprintJson).getAsJsonObject(); }
    public JsonObject summary() {
        var result = new JsonObject(); result.addProperty("machine_id", id); result.addProperty("label", label);
        result.addProperty("dimension", dimension); result.addProperty("blueprint_fingerprint", fingerprint);
        var at = new JsonArray(); at.add(anchor.x()); at.add(anchor.y()); at.add(anchor.z()); result.add("anchor", at);
        result.addProperty("last_build_state", lastBuildState); result.addProperty("registered_at_ms", registeredAtMillis);
        result.addProperty("last_built_at_ms", builtAtMillis); result.addProperty("current_world_verified", false);
        // 档案只提供读取范围；全量视图里的方块和状态必须重新从地图获取。
        if (captureMin != null) { result.add("capture_min",vector(captureMin)); result.add("capture_max",vector(captureMax)); }
        return result;
    }
    private static JsonArray vector(Position at) { var value = new JsonArray(); value.add(at.x()); value.add(at.y()); value.add(at.z()); return value; }
    // 同一世界、维度和锚点沿用机器编号；改图只更新蓝图版本，后续观察仍定位同一台机器。
    static String locationId(String identity, String dimension, Position anchor) {
        return "machine:" + CatalogLimits.hash(identity + "\n" + dimension + "\n" + anchor.x() + "," + anchor.y() + "," + anchor.z());
    }
}
