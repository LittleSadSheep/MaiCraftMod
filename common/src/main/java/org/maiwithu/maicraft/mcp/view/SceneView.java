// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.perception.FacilitySighting;
import org.maiwithu.maicraft.behavior.perception.HeardSound;
import org.maiwithu.maicraft.behavior.perception.OverheadGrid;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneEntity;
import org.maiwithu.maicraft.behavior.perception.SceneEnvironment;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.perception.TerrainFeature;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import java.util.Locale;

/**
 * observe(scene) 的样子：周围有什么、在哪个方位、多远，每样东西带观察编号，LLM 可以直接指着它下指令。
 *
 * <p>列表是完整的：场景模型给多少就交多少，数量很大时由场景模型在速写里按远近分组，这里不截断。
 * 方位同时给相对朝向（前方、左侧……）和东南西北，LLM 用哪个说都行。
 */
public final class SceneView {
    private SceneView() {}

    public static JsonObject scene(Scene scene, boolean withGrid) {
        JsonObject json = new JsonObject();
        SceneEnvironment environment = scene.environment();
        if (environment != null) {
            json.addProperty("time", environment.timeText());
            json.addProperty("weather", environment.weather());
            json.addProperty("light", environment.light());
            json.addProperty("biome", environment.biome());
        }
        if (scene.self() != null) {
            json.addProperty("facing", scene.self().facingWord());
        }
        json.addProperty("summary", scene.summary());
        JsonArray entities = new JsonArray();
        scene.entities().forEach(entity -> entities.add(entity(entity)));
        json.add("entities", entities);
        JsonArray heard = new JsonArray();
        scene.heard().forEach(sound -> heard.add(sound(sound)));
        json.add("heard", heard);
        JsonArray features = new JsonArray();
        scene.features().forEach(feature -> features.add(feature(feature)));
        json.add("features", features);
        JsonArray facilities = new JsonArray();
        scene.facilities().forEach(facility -> facilities.add(facility(facility)));
        json.add("facilities", facilities);
        if (withGrid && scene.grid() != null) {
            json.add("grid", grid(scene.grid()));
        }
        return json;
    }

    static JsonObject entity(SceneEntity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entity.id());
        json.addProperty("type", entity.type());
        if (entity.name() != null) json.addProperty("name", entity.name());
        json.addProperty("direction", entity.direction());
        json.addProperty("compass", entity.compass());
        json.addProperty("distance", entity.distance());
        json.addProperty("dy", entity.dy());
        json.addProperty("visible", entity.visible());
        json.addProperty("hostile", entity.hostile());
        json.addProperty("targeting_me", entity.targetingMe());
        if (!entity.traits().isEmpty()) {
            JsonObject traits = new JsonObject();
            entity.traits().forEach(traits::addProperty);
            json.add("traits", traits);
        }
        return json;
    }

    static JsonObject feature(TerrainFeature feature) {
        JsonObject json = new JsonObject();
        json.addProperty("id", feature.id());
        json.addProperty("kind", feature.kind());
        json.addProperty("direction", feature.direction());
        json.addProperty("compass", feature.compass());
        json.addProperty("distance", feature.distance());
        json.addProperty("size", feature.size());
        json.add("position", position(feature.center()));
        return json;
    }

    static JsonObject facility(FacilitySighting facility) {
        JsonObject json = new JsonObject();
        json.addProperty("id", facility.id());
        json.addProperty("block", facility.blockType());
        json.addProperty("direction", facility.direction());
        json.addProperty("compass", facility.compass());
        json.addProperty("distance", facility.distance());
        json.add("position", position(facility.position()));
        return json;
    }

    private static JsonObject sound(HeardSound sound) {
        JsonObject json = new JsonObject();
        json.addProperty("sound", sound.kind());
        json.addProperty("direction", sound.direction());
        json.addProperty("distance", sound.nearness());
        return json;
    }

    /** 俯视网格：一行一个字符串，第一行在北；附图例，读图不用另查说明。 */
    private static JsonObject grid(OverheadGrid.View view) {
        JsonObject json = new JsonObject();
        json.addProperty("legend", view.legend());
        json.addProperty("radius", view.radius());
        JsonArray rows = new JsonArray();
        for (char[] row : view.cells()) {
            rows.add(new String(row));
        }
        json.add("rows", rows);
        return json;
    }

    /** 坐标：同时给出，LLM 需要时可以用，但不要求它用坐标思考。 */
    static JsonObject position(WorldPosition position) {
        JsonObject json = new JsonObject();
        json.addProperty("x", position.x());
        json.addProperty("y", position.y());
        json.addProperty("z", position.z());
        if (position.dimension() != null) json.addProperty("dimension", position.dimension());
        return json;
    }

    /** 两点之间的水平距离（格），取整。 */
    static int distance(SceneSelf self, WorldPosition target) {
        double dx = target.x() + 0.5 - self.x();
        double dz = target.z() + 0.5 - self.z();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
