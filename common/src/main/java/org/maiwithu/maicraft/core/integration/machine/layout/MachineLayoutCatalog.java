// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed IDs are checked separately. Profiles describe audited physical interfaces, not recipes. */
final class MachineLayoutCatalog {
    record Profile(Map<String, String> state, Set<String> inputs, Set<String> outputs,
                   List<MachineLayoutRouting.Side> shafts, String evidence) {
        boolean accepts(String medium, boolean output) {
            return (output ? outputs : inputs).contains(medium);
        }
    }

    private static final Map<String, Profile> PROFILES = profiles();
    private MachineLayoutCatalog() {}
    static Profile get(String id) { return PROFILES.get(id); }

    private static Map<String, Profile> profiles() {
        Map<String, Profile> out = new LinkedHashMap<>();
        Map<String, String> north = Map.of("facing", "north");
        add(out, "minecraft:chest", north, Set.of("items"), Set.of("items"), "Vanilla container inventory");
        add(out, "minecraft:barrel", north, Set.of("items"), Set.of("items"), "Vanilla container inventory");
        add(out, "minecraft:hopper", Map.of("facing", "down"), Set.of("items"), Set.of("items"), "Vanilla hopper inventory");
        for (String id : List.of("enrichment_chamber", "crusher", "energized_smelter")) {
            add(out, "mekanism:" + id, north, Set.of("items", "energy"), Set.of("items"),
                    "Mekanism TileEntityElectricMachine.setupItemIOConfig/setupInputConfig; configurable side ports");
        }
        add(out, "mekanism:metallurgic_infuser", north, Set.of("items", "energy", "chemicals"), Set.of("items"),
                "TileEntityMetallurgicInfuser: item IO, energy input, infusion chemical input; chemical ejection disabled");
        add(out, "mekanism:electrolytic_separator", north, Set.of("fluids", "energy"), Set.of("chemicals"),
                "TileEntityElectrolyticSeparator: fluid input and separately configured chemical output tanks");
        for (String tier : List.of("basic", "advanced", "elite", "ultimate")) {
            add(out, "mekanism:" + tier + "_energy_cube", north, Set.of("energy"), Set.of("energy"),
                    "TileEntityEnergyCube.setupIOConfig(ENERGY)");
            add(out, "mekanism:" + tier + "_chemical_tank", north, Set.of("chemicals"), Set.of("chemicals"),
                    "TileEntityChemicalTank.setupIOConfig(CHEMICAL)");
            add(out, "mekanism:" + tier + "_fluid_tank", Map.of(), Set.of("fluids"), Set.of("fluids"),
                    "TileEntityFluidTank fluid capability; native extraction mode remains a configuration obligation");
        }
        for (String id : List.of("basin", "depot", "fluid_tank")) {
            Set<String> media = id.equals("fluid_tank") ? Set.of("fluids")
                    : id.equals("depot") ? Set.of("items") : Set.of("items", "fluids");
            add(out, "create:" + id, Map.of(), media, media,
                    "Create " + id + " BlockEntity.registerCapabilities; resource filters must be verified");
        }
        for (String id : List.of("shaft", "cogwheel", "large_cogwheel", "encased_chain_drive")) {
            out.put("create:" + id, new Profile(Map.of("axis", "y"), Set.of("kinetic"), Set.of("kinetic"),
                    List.of(MachineLayoutRouting.Side.DOWN, MachineLayoutRouting.Side.UP),
                    "Create axis kinetic blocks expose shafts along their rotation axis"));
        }
        out.put("create:millstone", new Profile(Map.of(), Set.of("items", "kinetic"), Set.of("items"),
                List.of(MachineLayoutRouting.Side.DOWN), "MillstoneBlock.hasShaftTowards(DOWN); MillstoneBlockEntity item capability"));
        // Mixers have NO shaft interface; a cogwheel coupling compiler is required for their drive.
        add(out, "create:mechanical_mixer", Map.of(), Set.of(), Set.of(),
                "MechanicalMixerBlock.hasShaftTowards returns false; process basin needs a dedicated station template");
        add(out, "create:mechanical_press", north, Set.of(), Set.of(),
                "MechanicalPressBlock has horizontal shafts; dedicated oriented process-station template required");
        for (String id : List.of("controller", "drive", "energy_cell", "dense_energy_cell", "molecular_assembler")) {
            add(out, "ae2:" + id, id.equals("drive") ? north : Map.of(), Set.of("ae_network"), Set.of("ae_network"),
                    "AEBlocks + AENetworkedBlockEntity exposed grid nodes; channel/power/configuration verified after placement");
        }
        add(out, "ae2:energy_acceptor", Map.of(), Set.of("energy", "ae_network"), Set.of("ae_network"),
                "EnergyAcceptorBlockEntity external FE input and AE grid node");
        add(out, "ae2:interface", Map.of(), Set.of("items", "fluids", "ae_network"), Set.of("items", "fluids", "ae_network"),
                "InterfaceBlockEntity interface inventory/fluid storage and AE grid node; configured stocking required");
        return Map.copyOf(out);
    }

    private static void add(Map<String, Profile> out, String id, Map<String, String> state,
                            Set<String> inputs, Set<String> outputs, String evidence) {
        out.put(id, new Profile(state, inputs, outputs, List.of(), evidence));
    }

    static String transport(String medium) {
        return switch (medium) {
            case "items" -> "mekanism:basic_logistical_transporter";
            case "fluids" -> "mekanism:basic_mechanical_pipe";
            case "energy" -> "mekanism:basic_universal_cable";
            case "chemicals" -> "mekanism:basic_pressurized_tube";
            case "kinetic" -> "create:encased_chain_drive";
            case "ae_network" -> MachineLayoutAeNetworks.DENSE;
            default -> null;
        };
    }
}
