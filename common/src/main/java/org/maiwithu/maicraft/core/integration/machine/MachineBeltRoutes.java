// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 折线只决定运输路径；拐角归接收段，物品交接和动力连通分别建模。 */
public final class MachineBeltRoutes {
    private MachineBeltRoutes() {}
    public static JsonArray expand(JsonArray entries, int radius, int lengthLimit) {
        JsonArray result = new JsonArray(); int targets = 0;
        for (var raw : entries) {
            if (!raw.isJsonObject() || !raw.getAsJsonObject().has("path")) { result.add(raw.deepCopy()); continue; }
            JsonObject route = raw.getAsJsonObject();
            for (String key : route.keySet()) if (!Set.of("type", "path", "pulleys").contains(key)) throw bad("belt_path_uses_point_order; omit " + key);
            if (!route.has("type") || !route.get("type").isJsonPrimitive() || !route.getAsJsonPrimitive("type").isString()
                    || !route.get("type").getAsString().equals("create:belt")) throw bad("unsupported_native_installation");
            if (!route.get("path").isJsonArray() || route.getAsJsonArray("path").size() < 2
                    || route.getAsJsonArray("path").size() > MachinePlanningBudget.current().maxConnections()) throw bad("invalid_belt_path");
            List<BlockPos> points = new ArrayList<>();
            for (var point : route.getAsJsonArray("path")) {
                BlockPos at = MachineAssemblyDocument.position(point, radius);
                if (!points.isEmpty()) direction(points.getLast(), at);
                // 连续共线点合并，让逐格路径与稀疏拐点都编译成相同的原生带段。
                if (points.size() > 1 && direction(points.get(points.size() - 2), points.getLast()) == direction(points.getLast(), at)) points.removeLast();
                points.add(at);
            }
            Set<BlockPos> pulleys = new HashSet<>();
            if (route.has("pulleys")) {
                if (!route.get("pulleys").isJsonArray() || route.getAsJsonArray("pulleys").size() > MachinePlanningBudget.current().maxTargets()) throw bad("invalid_belt_pulleys");
                for (var rawPulley : route.getAsJsonArray("pulleys"))
                    if (!pulleys.add(MachineAssemblyDocument.position(rawPulley, radius))) throw bad("belt_pulley_must_be_unique_and_on_span");
            }
            for (int i = 0; i < points.size() - 1; i++) {
                BlockPos first = points.get(i), end = points.get(i + 1); Direction motion = direction(first, end);
                // 转角那格属于后一条带，前一条停在相邻格；两段不会争用同一个端轴或方块实体。
                if (i < points.size() - 2) end = end.relative(motion.getOpposite());
                int remaining = first.distManhattan(end) + 1;
                if (remaining < 2) throw bad("belt_route_segment_too_short: " + first);
                while (remaining > 0) {
                    int count = Math.min(lengthLimit, remaining);
                    if (remaining - count == 1) count--;
                    if (count < 2 || lengthLimit < 2) throw bad("belt_route_segment_too_short: " + first);
                    targets += count;
                    if (targets > MachinePlanningBudget.current().maxTargets() || result.size() >= MachinePlanningBudget.current().maxConnections()) throw bad("native_installation_target_budget_exceeded");
                    BlockPos last = first.relative(motion, count - 1); JsonObject segment = new JsonObject();
                    segment.addProperty("type", "create:belt"); segment.add("first", MachineAssemblyDocument.json(first));
                    segment.add("second", MachineAssemblyDocument.json(last)); segment.addProperty("flow", "first_to_second");
                    JsonArray onSegment = new JsonArray();
                    for (int step = 0; step < count; step++) {
                        BlockPos at = first.relative(motion, step); if (pulleys.remove(at)) onSegment.add(MachineAssemblyDocument.json(at));
                    }
                    if (!onSegment.isEmpty()) segment.add("pulleys", onSegment); result.add(segment);
                    first = last.relative(motion); remaining -= count;
                }
            }
            if (!pulleys.isEmpty()) throw bad("belt_pulley_must_be_unique_and_on_span");
        }
        if (result.size() > MachinePlanningBudget.current().maxConnections()) throw bad("native_installation_target_budget_exceeded");
        return result;
    }
    private static Direction direction(BlockPos from, BlockPos to) {
        int x = to.getX() - from.getX(), y = to.getY() - from.getY(), z = to.getZ() - from.getZ();
        if (y != 0 || (x == 0) == (z == 0)) throw bad("belt_path_requires_horizontal_orthogonal_legs; use first/second for native sloped spans");
        return x == 0 ? z > 0 ? Direction.SOUTH : Direction.NORTH : x > 0 ? Direction.EAST : Direction.WEST;
    }
    public static JsonArray handoffs(JsonObject blueprint, Set<String> errors) {
        JsonArray result = new JsonArray(); var spans = MachineAssemblyDocument.belts(blueprint);
        if (spans.isEmpty()) return result;
        var rows = blueprint.getAsJsonObject("assembly").getAsJsonArray("installations");
        for (int i = 0; i < spans.size(); i++) {
            var source = spans.get(i); JsonObject input = rows.get(i).getAsJsonObject();
            if (!source.processesItems() || !input.has("flow")) continue;
            boolean forward = input.get("flow").getAsString().equals("first_to_second");
            Direction motion = forward ? source.facing() : source.facing().getOpposite();
            BlockPos outlet = forward ? source.second() : source.first(), inlet = outlet.relative(motion);
            for (int j = 0; j < spans.size(); j++) if (i != j && spans.get(j).cells().contains(inlet)) {
                JsonObject transfer = new JsonObject(); transfer.add("from", MachineAssemblyDocument.json(outlet));
                transfer.add("to", MachineAssemblyDocument.json(inlet)); transfer.addProperty("incoming_direction", motion.getName());
                var receiver = spans.get(j); var receiverInput = rows.get(j).getAsJsonObject(); Boolean compatible = null;
                if (!receiver.processesItems()) compatible = false;
                else if (receiverInput.has("flow")) {
                    Direction receiving = receiverInput.get("flow").getAsString().equals("first_to_second") ? receiver.facing() : receiver.facing().getOpposite();
                    compatible = receiving != motion.getOpposite();
                }
                if (Boolean.FALSE.equals(compatible)) errors.add("belt_handoff_incompatible: " + outlet + " -> " + inlet);
                transfer.addProperty("declared_flow_compatible", compatible); transfer.addProperty("runtime_transfer_verified", false);
                transfer.addProperty("power_connection_implied", false); result.add(transfer);
            }
        }
        return result;
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
