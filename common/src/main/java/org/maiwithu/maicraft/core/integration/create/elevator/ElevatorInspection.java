package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** Bounded synchronized client evidence; this never opens a screen, scrolls or sends packets. */
final class ElevatorInspection {
    private static final CreateElevatorBridge BRIDGE = CreateElevatorBridge.optional();
    static CreateElevatorBridge bridge() { return BRIDGE; }

    static Map<String, Object> inspect(LocalPlayer player) {
        if (BRIDGE == null || player == null || player.clientLevel == null) return Map.of("integrationAvailable", false);
        try {
            var cabins = new ArrayList<Map<String, Object>>();
            for (var cabin : BRIDGE.cabins(player)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("entity_id", cabin.entity().getId()); data.put("entity_uuid", cabin.entity().getUUID().toString());
                data.put("column", Map.of("x", cabin.column().x(), "z", cabin.column().z(), "facing", cabin.column().side().getSerializedName()));
                data.put("contact_y_offset", cabin.offset()); data.put("current_contact_y", cabin.entity().getY() + cabin.offset());
                data.put("target_contact_y", cabin.targetY()); data.put("arrived_hint", cabin.arrived());
                data.put("aligned_at_target", cabin.aligned(cabin.targetY())); data.put("origin", point(cabin.origin()));
                data.put("body_supported_by_cabin", supports(cabin, player));
                data.put("floors", cabin.floors().stream().map(f -> Map.of("contact_y", f.contactY(), "short_name", f.shortName(),
                        "long_name", f.longName(), "in_rope_range", cabin.serves(f.contactY()))).toList());
                data.put("controls", cabin.controls().stream().map(p -> Map.of("local_position", point(Vec3.atLowerCornerOf(p)),
                        "world_aim", point(BRIDGE.controlAim(cabin, p)), "selected_contact_y", BRIDGE.selected(cabin, p))).toList());
                cabins.add(Map.copyOf(data));
            }
            return Map.of("integrationAvailable", true, "cabins", cabins, "limit", 32,
                    "call_inputs", "native adjacent buttons or matched handheld-linked-controller channels; discovered during route survey",
                    "boarding", "dynamic support and door clearance are verified by the session; not equivalent to passenger state");
        } catch (RuntimeException failure) {
            return Map.of("integrationAvailable", false, "reason", String.valueOf(failure.getMessage()));
        }
    }

    static Map<String, Object> probe(LocalPlayerContext ctx, BlockPos destination) {
        if (BRIDGE == null) return Map.of("available", false, "reason", "Create elevator API is absent", "estimatedTicks", -1);
        try {
            var cabins = BRIDGE.cabins(ctx);
            boolean missingList = cabins.stream().anyMatch(c -> c.entity().distanceToSqr(ctx.player()) < 128 * 128 && c.floors().isEmpty());
            boolean candidate = missingList || cabins.stream().anyMatch(c -> c.entity().distanceToSqr(ctx.player()) < 128 * 128
                    && c.floors().stream().anyMatch(f -> c.serves(f.contactY()) && (c.controls().isEmpty()
                            ? Math.abs(f.contactY() - destination.getY()) <= 3
                            : c.controls().stream().anyMatch(p -> Math.abs(f.contactY() - c.offset() + p.getY() - destination.getY()) <= 3))));
            return Map.of("available", candidate, "reason", missingList ? "needs_floor_sync" : candidate ? "loaded served-floor candidate; entrances and controls still require survey" : "no synchronized served-floor candidate",
                    "estimatedTicks", candidate ? 40 + (int) Math.ceil(Math.abs(destination.getY() - ctx.player().getY()) * 20) : -1,
                    "estimate_only", true, "route_verified", false);
        } catch (RuntimeException failure) {
            return Map.of("available", false, "reason", String.valueOf(failure.getMessage()), "estimatedTicks", -1);
        }
    }

    static Map<String, Double> point(Vec3 point) { return Map.of("x", point.x, "y", point.y, "z", point.z); }
    static boolean supports(CreateElevatorBridge.Cabin cabin, LocalPlayer player) {
        Vec3 feet = cabin.local(player.position());
        var supports = new ArrayList<net.minecraft.world.phys.AABB>();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(feet).offset(-1, -1, -1), BlockPos.containing(feet).offset(1, 0, 1))) {
            var block = cabin.blocks().get(pos);
            if (block != null) for (var box : block.state().getCollisionShape(cabin.view(), pos).toAabbs()) supports.add(box.move(pos));
        }
        return ElevatorGeometry.supported(feet, supports, player.getBbWidth());
    }
}
