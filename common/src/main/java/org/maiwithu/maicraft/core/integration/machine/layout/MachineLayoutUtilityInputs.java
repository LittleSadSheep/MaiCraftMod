// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Bounds;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Cell;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Pos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Side;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import static org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout.position;

/** One passive entry feeds its declared internal network; utility production stays outside the machine. */
final class MachineLayoutUtilityInputs {
    record Consumer(String name, String instanceId, String blockId, Pos position, List<Side> sides) {}
    private MachineLayoutUtilityInputs() {}

    static void compile(MachineLayoutWork work, MachineUtilityInputs.Declaration input, List<Consumer> consumers, Bounds bounds) {
        if (consumers.isEmpty()) { work.fail("external_input_without_consumer", input.id()); return; }
        Side face = Side.valueOf(input.face().name()); boolean kinetic = input.medium().equals("kinetic");
        Pos port = choosePosition(work, face, consumers.getFirst().position, kinetic, bounds);
        if (port == null) { work.fail("external_input_space_unavailable", input.id() + " needs an accessible exterior port and an internal relay within the site."); return; }
        String owner = "external_input:" + input.id(), connector = MachineUtilityInputs.connector(input.medium());
        Map<String, String> state = kinetic ? Map.of("axis", input.face().getAxis().getName())
                : connector.equals("minecraft:barrel") ? Map.of("facing", "north") : Map.of();
        work.add(new Cell(port, connector, state, false, "component:" + owner));
        Pos source = port; Cell gearbox = null;
        List<Side> exits = kinetic ? List.of(opposite(face)) : java.util.Arrays.stream(Side.values()).filter(side -> side != face).toList();
        if (kinetic && face.y == 0) {
            source = port.step(opposite(face));
            gearbox = new Cell(source, "create:gearbox", Map.of("axis", face.x != 0 ? "z" : "x"), false, "component:" + owner);
            work.add(gearbox); exits = List.of(Side.UP, Side.DOWN);
        }
        // Keep internal routes out of the exterior ray; only the adjoining hookup cell needs construction clearance.
        for (Pos clear = port.step(face); bounds.contains(clear); clear = clear.step(face)) work.utilityRays.add(clear);
        if (bounds.contains(port.step(face))) work.clearance.add(port.step(face));
        var concrete = input.at(new BlockPos(port.x(), port.y(), port.z()));
        work.externalInputs.add(concrete.json());
        JsonObject component = new JsonObject(); component.addProperty("name", owner); component.addProperty("instance_id", owner);
        component.addProperty("block_id", connector); component.addProperty("role", "passive " + input.medium() + " input from an existing utility network");
        component.add("offset", position(port)); component.addProperty("interface_evidence", "declared passive boundary; source supply remains unverified");
        work.components.add(component);
        for (Consumer consumer : consumers) route(work, input, owner, port, source, exits, gearbox, consumer, bounds);
        work.pending("external_utility_connection", input.id(), "Build the machine first, then connect an observed existing " + input.medium()
                + " network to " + port + "." + face.label() + "; verify native supply and capacity before commissioning.");
    }

    private static void route(MachineLayoutWork work, MachineUtilityInputs.Declaration input, String owner, Pos port, Pos source,
                              List<Side> exits, Cell gearbox, Consumer consumer, Bounds bounds) {
        String medium = input.medium(), transport = MachineLayoutCatalog.transport(medium);
        var routingClearance = new HashSet<>(work.clearance); routingClearance.addAll(work.utilityRays);
        var route = MachineLayoutRouting.route(source, consumer.position, exits, consumer.sides, medium, transport, owner,
                work.cells, routingClearance, bounds);
        if (route == null) {
            work.fail("external_input_route_unresolved", input.id() + " -> " + consumer.name + " cannot be routed safely inside the site."); return;
        }
        for (Cell cell : route.cells()) if (!work.cells.containsKey(cell.position())) work.add(cell);
        JsonObject row = new JsonObject(); row.addProperty("id", owner + ":" + consumer.instanceId);
        row.addProperty("from", owner); row.addProperty("to", consumer.name);
        row.addProperty("from_instance_id", owner); row.addProperty("to_instance_id", consumer.instanceId);
        row.addProperty("medium", medium); row.addProperty("purpose", "distribute the declared external utility to this consumer");
        row.addProperty("external_input_id", input.id()); row.addProperty("transport_id", transport);
        row.add("source_offset", position(port)); row.add("destination_offset", position(consumer.position));
        row.addProperty("source_side", gearbox == null ? route.sourceSide().label() : opposite(Side.valueOf(input.face().name())).label());
        row.addProperty("destination_side", route.destinationSide().label());
        row.addProperty("physical_path_compiled", true); row.addProperty("resource_transfer_verified", false);
        JsonArray path = new JsonArray(); if (gearbox != null) path.add(position(gearbox.position()));
        route.cells().forEach(cell -> path.add(position(cell.position()))); row.add("route", path); work.routes.add(row);
        if (input.resource() != null) row.addProperty("resource", input.resource());
        if (!medium.equals("kinetic")) {
            if (consumer.blockId.startsWith("mekanism:") && !consumer.blockId.endsWith("_fluid_tank") && !consumer.blockId.equals("mekanism:induction_port"))
                work.configure(consumer.position, route.destinationSide(), medium, "input");
            // The item boundary is a real barrel; only its declared internal network may pull from it.
            if (medium.equals("items")) work.configure(route.cells().getFirst().position(), opposite(route.sourceSide()), medium, "pull");
        }
        work.pending("external_input_commissioning", input.id() + ":" + consumer.instanceId,
                "Internal topology is compiled; observe native " + medium + " delivery to " + consumer.name + " after the separate external hookup.");
    }

    private static Pos choosePosition(MachineLayoutWork work, Side face, Pos near, boolean kinetic, Bounds bounds) {
        int extreme = switch (face) {
            case EAST -> work.cells.keySet().stream().mapToInt(Pos::x).max().orElse(0) + 2;
            case WEST -> work.cells.keySet().stream().mapToInt(Pos::x).min().orElse(0) - 2;
            case UP -> work.cells.keySet().stream().mapToInt(Pos::y).max().orElse(0) + 2;
            case DOWN -> work.cells.keySet().stream().mapToInt(Pos::y).min().orElse(0) - 2;
            case SOUTH -> work.cells.keySet().stream().mapToInt(Pos::z).max().orElse(0) + 2;
            case NORTH -> work.cells.keySet().stream().mapToInt(Pos::z).min().orElse(0) - 2;
        };
        List<Pos> candidates = new ArrayList<>();
        for (int a = -8; a <= 8; a++) for (int b = -8; b <= 8; b++) {
            Pos at = face.x != 0 ? new Pos(extreme, near.y() + a, near.z() + b)
                    : face.y != 0 ? new Pos(near.x() + a, extreme, near.z() + b)
                    : new Pos(near.x() + a, near.y() + b, extreme);
            if (bounds.contains(at)) candidates.add(at);
        }
        candidates.sort(Comparator.comparingInt((Pos at) -> at.distance(near)).thenComparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        for (Pos at : candidates) {
            MachineLayoutRouting.checkpoint();
            if (!free(work, at)) continue;
            if (kinetic && face.y == 0 && (!bounds.contains(at.step(opposite(face))) || !free(work, at.step(opposite(face)))
                    || java.util.Arrays.stream(Side.values()).anyMatch(side -> work.cells.containsKey(at.step(opposite(face)).step(side))))) continue;
            boolean clear = true;
            for (Pos outward = at.step(face); bounds.contains(outward); outward = outward.step(face)) if (work.cells.containsKey(outward)) { clear = false; break; }
            if (clear) return at;
        }
        return null;
    }
    private static boolean free(MachineLayoutWork work, Pos at) { return !work.cells.containsKey(at) && !work.clearance.contains(at) && !work.utilityRays.contains(at); }
    private static Side opposite(Side side) {
        return switch (side) { case EAST -> Side.WEST; case WEST -> Side.EAST; case UP -> Side.DOWN; case DOWN -> Side.UP; case SOUTH -> Side.NORTH; case NORTH -> Side.SOUTH; };
    }
}
