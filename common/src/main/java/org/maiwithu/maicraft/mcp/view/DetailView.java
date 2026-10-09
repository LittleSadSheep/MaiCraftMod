// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import java.util.Locale;
import java.util.Optional;

/**
 * observe(detail) 的样子：一个看到的东西（按观察编号）或一个记住的地点（按地标名）的细节。
 *
 * <p>实体给能看出来的特征；地形特征给范围；设施给最近一次打开时看到的内容和时间。
 * 编号对应的东西已经不在场景里时，给最后一次看到它的方位与位置，不假装还看得见。
 */
public final class DetailView {
    private DetailView() {}

    /** 找不到这个编号、也没有叫这个名字的地标时为空。 */
    public static Optional<JsonObject> of(Scene scene, WorldMemory memory, String id) {
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("[efb][0-9]+")) {
            return scene.lookup(normalized).map(entry -> seen(scene, memory, entry));
        }
        return memory.place(id.trim()).map(position -> landmark(scene.self(), id.trim(), position));
    }

    private static JsonObject seen(Scene scene, WorldMemory memory, SeenRegistry.Entry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.id());
        json.addProperty("kind", SceneView.lower(entry.kind()));
        json.addProperty("last_direction", entry.direction());
        json.add("last_position", SceneView.position(entry.position()));
        json.addProperty("last_seen_tick", entry.lastSeenTick());
        // 此刻还在场景里：附上场景给的完整样子。
        scene.entities().stream().filter(entity -> entity.id().equals(entry.id())).findFirst()
                .ifPresent(entity -> json.add("now", SceneView.entity(entity)));
        scene.features().stream().filter(feature -> feature.id().equals(entry.id())).findFirst()
                .ifPresent(feature -> json.add("now", SceneView.feature(feature)));
        scene.facilities().stream().filter(facility -> facility.id().equals(entry.id())).findFirst()
                .ifPresent(facility -> json.add("now", SceneView.facility(facility)));
        if (!json.has("now")) {
            json.addProperty("in_scene", false);
        }
        // 设施：世界记忆里这个位置记过什么（开过的容器记得里面有什么）。
        JsonArray remembered = new JsonArray();
        for (MemoryRecord record : memory.recordsNear(entry.position(), 0.5)) {
            remembered.add(WorldMemoryView.record(record, scene.self()));
        }
        if (!remembered.isEmpty()) {
            json.add("remembered", remembered);
        }
        return json;
    }

    private static JsonObject landmark(SceneSelf self, String name, WorldPosition position) {
        JsonObject json = new JsonObject();
        json.addProperty("landmark", name);
        json.add("position", SceneView.position(position));
        if (self != null) {
            double dx = position.x() + 0.5 - self.x();
            double dz = position.z() + 0.5 - self.z();
            json.addProperty("direction", DirectionWords.relative(dx, dz, self.facingYaw()));
            json.addProperty("compass", DirectionWords.compassOf(dx, dz));
            json.addProperty("distance", SceneView.distance(self, position));
        }
        return json;
    }
}
