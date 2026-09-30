// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** 现场变化或旧引用丢失时，一起交付同名同址的新观察，不让模型再单独发起勘测。 */
public final class MachineSnapshotRejection extends IllegalArgumentException {
    private final String code;
    private final String previousId;
    private final MachineSnapshots.Snapshot latest;

    public MachineSnapshotRejection(String code, String previousId, MachineSnapshots.Snapshot latest) {
        super(code + ": " + ("machine_snapshot_changed".equals(code) ? "observed structure changed" : "previous observation is unavailable")
                + "; review the returned latest_snapshot before retrying");
        this.code = code;
        this.previousId = previousId;
        this.latest = latest;
    }

    /** 新编号与目标成对返回；原生补料或运行证据没有读取时，保持新观察自身的完整性标记。 */
    public Map<String, Object> details() {
        JsonObject snapshot = latest.report();
        JsonObject target = new JsonObject();
        target.addProperty("kind", "landmark");
        target.addProperty("label", latest.label());
        snapshot.add("target", target);
        if (snapshot.has("construction_site")) {
            // 工地的完整体积可能很大；同时交付已有感知使用的连续行几何，让模型直接看到地面和障碍。
            JsonObject geometry = ConstructionSiteGeometry.describe(latest);
            for (String key : List.of("snapshot_id", "dimension", "anchor", "target", "next_step", "validity", "observation_only"))
                geometry.remove(key);
            snapshot.add("site_geometry", geometry);
        }
        return Map.of("failure_code", code, "previous_snapshot_id", previousId,
                "latest_snapshot", snapshot,
                "next_action", "Review latest_snapshot and use its target and snapshot_id in the corrected goal; unchanged requests do not need another perceive or inspect_machine call.");
    }
}
