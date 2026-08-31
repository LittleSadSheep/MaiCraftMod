// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Registry-name hints only. A role is never evidence of a working connection or recipe. */
final class MachineSurveyModel {
    static final int MAX_RADIUS = 8;
    record Hint(String family, List<String> roles, boolean relevant) {}
    record Point(int x, int y, int z) {}
    record Component(Point point, int blockIndex) {}
    record Edge(int fromBlock, int toBlock, String face) {}
    record Adjacencies(List<Edge> edges, int omitted) {}

    private MachineSurveyModel() {}

    static Hint classify(String registryId) {
        String id = registryId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        return switch (namespace) {
            case "create" -> new Hint("create", createRoles(path), true);
            case "ae2", "appliedenergistics2" -> new Hint("ae2", ae2Roles(path), true);
            case "mekanism", "mekanismgenerators", "mekanismadditions" ->
                    new Hint("mekanism", mekanismRoles(path), true);
            case "minecraft" -> {
                boolean inventory = containsAny(path, "chest", "barrel", "hopper", "furnace",
                        "dispenser", "dropper", "shulker_box", "brewing_stand", "crafter");
                boolean redstone = containsAny(path, "redstone", "comparator", "repeater", "lever",
                        "button", "observer", "piston");
                yield new Hint("minecraft", inventory ? List.of("possible_inventory_or_processing")
                        : redstone ? List.of("possible_redstone_control") : List.of(), inventory || redstone);
            }
            default -> new Hint(namespace, List.of(), false);
        };
    }

    private static List<String> createRoles(String path) {
        if (containsAny(path, "water_wheel", "windmill_bearing", "steam_engine", "creative_motor")) {
            return List.of("possible_rotational_source");
        }
        if (containsAny(path, "shaft", "cogwheel", "gearbox", "clutch", "gearshift", "rotation_speed")) {
            return List.of("possible_rotational_transmission");
        }
        if (containsAny(path, "fluid_pipe", "pump", "valve", "fluid_tank")) {
            return List.of("possible_fluid_transport_or_storage");
        }
        if (containsAny(path, "belt", "funnel", "chute", "tunnel", "depot", "item_vault")) {
            return List.of("possible_item_transport_or_storage");
        }
        if (containsAny(path, "mixer", "press", "millstone", "crushing", "fan", "deployer",
                "drill", "saw", "spout", "basin", "crafter")) {
            return List.of("possible_processing_or_actuation");
        }
        return List.of("unclassified_create_component");
    }

    private static List<String> ae2Roles(String path) {
        if (path.contains("cable_bus")) return List.of("possible_multipart_network_host");
        if (containsAny(path, "cable", "controller", "energy_acceptor")) {
            return List.of("possible_me_network_infrastructure");
        }
        if (containsAny(path, "drive", "chest", "cell", "storage")) {
            return List.of("possible_me_storage");
        }
        if (containsAny(path, "pattern", "assembler", "crafting")) {
            return List.of("possible_me_crafting");
        }
        if (containsAny(path, "interface", "import", "export", "terminal")) {
            return List.of("possible_me_access_or_transfer");
        }
        return List.of("unclassified_ae2_component");
    }

    private static List<String> mekanismRoles(String path) {
        if (path.contains("universal_cable")) return List.of("possible_energy_transport");
        if (path.contains("mechanical_pipe")) return List.of("possible_fluid_transport");
        if (path.contains("pressurized_tube")) return List.of("possible_chemical_transport");
        if (path.contains("logistical_transporter")) return List.of("possible_item_transport");
        if (containsAny(path, "generator", "solar_panel", "wind_generator")) {
            return List.of("possible_energy_source");
        }
        if (containsAny(path, "tank", "bin", "energy_cube")) {
            return List.of("possible_resource_storage");
        }
        if (containsAny(path, "factory", "chamber", "crusher", "smelter", "separator", "infuser",
                "infusing", "enrichment", "purification", "injection", "oxidizer", "dissolution")) {
            return List.of("possible_processing");
        }
        return List.of("unclassified_mekanism_component");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    /** Only geometric touching-face evidence. Inspect each unordered pair once with linear work. */
    static Adjacencies adjacent(List<Component> components, int maxEdges) {
        Map<Point, Integer> lookup = new HashMap<>();
        for (Component component : components) lookup.put(component.point, component.blockIndex);
        List<Edge> edges = new ArrayList<>();
        int omitted = 0;
        for (Component component : components) {
            Point point = component.point;
            Point[] neighbors = {new Point(point.x + 1, point.y, point.z),
                    new Point(point.x, point.y + 1, point.z), new Point(point.x, point.y, point.z + 1)};
            String[] faces = {"east", "up", "south"};
            for (int index = 0; index < neighbors.length; index++) {
                Integer other = lookup.get(neighbors[index]);
                if (other == null) continue;
                if (edges.size() >= Math.max(0, maxEdges)) omitted++;
                else edges.add(new Edge(component.blockIndex, other, faces[index]));
            }
        }
        return new Adjacencies(List.copyOf(edges), omitted);
    }

    static int boundedRadius(int radius) {
        return Math.max(0, Math.min(MAX_RADIUS, radius));
    }
}
