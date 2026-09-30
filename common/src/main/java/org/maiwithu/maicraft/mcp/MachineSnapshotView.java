package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Function;

/** 失败附带的新现场优先显示编号、目标和实际几何；大明细仍指向本次已取得的观察。 */
final class MachineSnapshotView {
    record Observation(JsonObject snapshot, JsonObject owner, String path) {}

    private MachineSnapshotView() {}

    // 只沿公开任务结果与决策回执寻找新现场，不把旧目标或蓝图里的同名字段当成恢复依据。
    static Observation find(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        if (object.has("latest_snapshot") && object.get("latest_snapshot").isJsonObject())
            return new Observation(object.getAsJsonObject("latest_snapshot"), object, path + "/latest_snapshot");
        for (String key : List.of("task", "terminal", "result", "decision", "context", "data", "last_step")) {
            Observation found = find(object.get(key), path + "/" + key);
            if (found != null) return found;
        }
        return null;
    }

    static JsonObject present(JsonObject snapshot, String path, int budget, Function<String, String> uri) {
        if (JsonReadback.fits(snapshot, budget)) return snapshot.deepCopy();
        JsonObject result = new JsonObject();
        // 即使整份观察较大，模型也能在首份失败回执中直接取得可提交的新编号和同址目标。
        for (String key : List.of("snapshot_id", "target", "label", "dimension", "radius", "structure_fingerprint",
                "structure_complete", "complete", "validity", "observation_only"))
            if (snapshot.has(key)) result.add(key, snapshot.get(key).deepCopy());
        result.addProperty("omitted", true);
        result.addProperty("detail_path", path);
        if (uri != null) result.addProperty("resource_uri", uri.apply(path));
        // 几何和原生过程按独立字段展示或给出直达路径，不用多层通用摘要藏住新场地。
        for (String key : List.of("palette", "relative_blocks", "native_processes", "control_analysis", "server_evidence")) {
            if (!snapshot.has(key)) continue;
            int remaining = budget - result.toString().length() - 100;
            if (remaining < 200) break;
            result.add(key, JsonReadback.preview(snapshot.get(key), path + "/" + key, Math.min(900, remaining), uri));
            if (!JsonReadback.fits(result, budget)) { result.remove(key); break; }
        }
        return result;
    }
}
