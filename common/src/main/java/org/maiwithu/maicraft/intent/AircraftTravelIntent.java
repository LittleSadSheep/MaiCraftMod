package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftTravelTaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;

/** 显式选择已登记飞机长途旅行；aircraft_id 与只请求登船的 structure_id 分开。 */
final class AircraftTravelIntent {
    private AircraftTravelIntent() {}
    static boolean applies(Goal goal) {
        var p = goal.parameters();
        return p.has("aircraft_id") || p.has("transport_mode") && "aircraft".equals(p.get("transport_mode").getAsString());
    }
    static void validate(Goal goal) {
        var p = goal.parameters();
        if (!p.has("transport_mode") || !"aircraft".equals(p.get("transport_mode").getAsString()) || !p.has("aircraft_id"))
            throw new IllegalArgumentException("aircraft travel requires transport_mode=aircraft and a registered aircraft_id");
        UUID.fromString(p.get("aircraft_id").getAsString());
        // 飞机落点与终点不同，保留既有精度契约；未定位探索由独立探索流程处理。
        TravelDestination.validatePrecision(p); TravelDestination.fromGoal(goal);
        for (String field : List.of("structure_id", "seat_position", "block_id", "block", "semantic_target", "biome_id", "biome_tag",
                "direction", "angle_degrees", "min_distance", "max_distance", "elevator_floor"))
            if (p.has(field)) throw new IllegalArgumentException("located aircraft travel does not accept " + field);
        if (goal.target() == null && !p.has("destination")) throw new IllegalArgumentException("aircraft travel needs a located destination");
        if (goal.target() != null && !List.of("coordinates", "landmark", "area").contains(goal.target().kind()))
            throw new IllegalArgumentException("aircraft travel target must be coordinates or a remembered place");
        if (p.has("cruise_altitude")) number(p, "cruise_altitude", 0);
    }
    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        validate(goal); var p = goal.parameters();
        TravelDestination at = TravelDestination.fromGoal(goal);
        if (at == null) {
            var target = goal.target();
            var place = target.kind().equals("coordinates") ? null : runtime.landmark(target.label());
            var position = target.kind().equals("coordinates") ? target.position() : place == null ? null : place.position();
            if (position == null) throw new IllegalArgumentException("aircraft destination has no remembered coordinates");
            at = new TravelDestination(position.x(), (double) position.y(), position.z(), position.dimension());
        }
        if (at.dimension() != null && !at.dimension().equals(player.level().dimension().location().toString()))
            throw new IllegalArgumentException("aircraft destination must be in the current dimension");
        String call = "aircraft-travel-" + UUID.randomUUID(); long deadline = player.level().getGameTime() + 20L * 60 * 60;
        boolean terrain = bool(p, "may_alter_terrain") || bool(goal.preferences(), "may_alter_terrain");
        var arrival = new MoveToTaskRecord(call, deadline, at.x(), at.y(), at.z(), null, terrain,
                bool(p, "allow_water_bucket_fall"), TransportMode.GROUND, bool(p, "allow_landing_assists"),
                bool(p, "exact"), number(p, "horizontal_radius", 3), number(p, "vertical_tolerance", 2));
        return new IntentAction.Native(new AircraftTravelTaskRecord(call, deadline, UUID.fromString(p.get("aircraft_id").getAsString()),
                p.has("cruise_altitude") ? number(p, "cruise_altitude", 0) : null, arrival));
    }
    private static boolean bool(JsonObject p, String key) { return p.has(key) && p.get(key).getAsBoolean(); }
    private static double number(JsonObject p, String key, double fallback) {
        if (!p.has(key)) return fallback;
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isNumber() || !Double.isFinite(p.get(key).getAsDouble()))
            throw new IllegalArgumentException(key + " requires a finite number");
        return p.get(key).getAsDouble();
    }
}
