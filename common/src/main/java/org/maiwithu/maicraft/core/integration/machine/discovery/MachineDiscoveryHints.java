// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.util.List;

/** Registry names suggest component roles; they do not establish recipes, factory boundaries or working connections. */
final class MachineDiscoveryHints {
    record Hint(String family, List<String> roles, String basis) {}
    private MachineDiscoveryHints() {}

    static Hint classify(MachineDiscoveryScanner.BlockSample sample) {
        String id = sample.blockId(); int colon = id.indexOf(':');
        String namespace = colon < 0 ? "" : id.substring(0, colon), path = colon < 0 ? id : id.substring(colon + 1);
        String family = namespace.startsWith("create") ? "create"
                : namespace.equals("ae2") || namespace.equals("appliedenergistics2") ? "ae2"
                : namespace.startsWith("mekanism") ? "mekanism" : null;
        if (family == null && namespace.equals("minecraft") && path.equals("ender_chest"))
            return new Hint("minecraft", List.of("possible_personal_inventory_access"), "registry_name_heuristic_not_verified_function");
        if (family == null) return Boolean.TRUE.equals(sample.nativeContainer())
                ? new Hint(namespace, List.of("possible_inventory"), "native_container_interface") : null;
        String role = switch (family) {
            case "create" -> contains(path, "water_wheel", "windmill_bearing", "steam_engine", "creative_motor") ? "possible_rotational_source"
                    : contains(path, "shaft", "cogwheel", "gearbox", "clutch", "gearshift", "rotation_speed") ? "possible_rotational_transmission"
                    : contains(path, "fluid_pipe", "pump", "valve", "fluid_tank") ? "possible_fluid_transport_or_storage"
                    : contains(path, "belt", "funnel", "chute", "tunnel", "depot", "item_vault") ? "possible_item_transport_or_storage"
                    : contains(path, "mixer", "press", "millstone", "crushing", "fan", "deployer", "drill", "saw", "spout", "basin", "crafter")
                    ? "possible_processing_or_actuation" : "unclassified_create_component";
            case "ae2" -> contains(path, "pattern", "assembler", "crafting") ? "possible_me_crafting"
                    : contains(path, "interface", "import", "export", "terminal") ? "possible_me_access_or_transfer"
                    : contains(path, "drive", "chest", "cell", "storage") ? "possible_me_storage"
                    : "possible_me_network_component";
            default -> contains(path, "universal_cable") ? "possible_energy_transport"
                    : contains(path, "mechanical_pipe") ? "possible_fluid_transport"
                    : contains(path, "pressurized_tube") ? "possible_chemical_transport"
                    : contains(path, "transporter", "sorter") ? "possible_item_transport"
                    : contains(path, "generator", "solar_panel") ? "possible_energy_source"
                    : contains(path, "tank", "bin", "energy_cube") ? "possible_resource_storage"
                    : contains(path, "factory", "chamber", "crusher", "smelter", "separator", "infuser", "enrichment", "purification", "injection")
                    ? "possible_processing" : "unclassified_mekanism_component";
        };
        return new Hint(family, List.of(role), "registry_name_heuristic_not_verified_function");
    }

    private static boolean contains(String path, String... clues) {
        for (String clue : clues) if (path.contains(clue)) return true;
        return false;
    }
}
