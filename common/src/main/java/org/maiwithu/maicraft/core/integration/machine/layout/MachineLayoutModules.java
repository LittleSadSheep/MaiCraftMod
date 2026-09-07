// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismMatrixTemplate;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Cell;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Pos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Side;

/** Composable process modules, with transport ports on real internal devices. */
final class MachineLayoutModules {
    record Port(Pos offset, String blockId, List<Side> sides) {}
    record Module(String id, String coreBlock, List<Cell> cells, Map<String, Port> ports,
                  Set<Pos> clearance, JsonArray configurations, JsonArray initialContents, JsonArray seals, JsonObject commissioning) {
        Port port(String medium, boolean output) { return ports.get(medium + (output ? ":out" : ":in")); }
        Pos coreOffset() { return cells.stream().filter(c -> c.id().equals(coreBlock)).findFirst().orElseThrow().position(); }
        int span() {
            int extent = 0;
            for (Cell c : cells) extent = Math.max(extent, Math.max(Math.abs(c.position().x()), Math.abs(c.position().z())));
            for (Pos p : clearance) extent = Math.max(extent, Math.max(Math.abs(p.x()), Math.abs(p.z())));
            return 2 * extent + 3;
        }
    }
    private MachineLayoutModules() {}
    static Module resolve(JsonObject component, SemanticMachineLayout.Registry registry) {
        if (!component.has("module")) return null;
        String id = component.get("module").getAsString();
        if (!id.equals("mekanism:induction_matrix") && (component.has("module_tier") || component.has("module_options") && !id.startsWith("ae2:"))) {
            throw new IllegalArgumentException("module_tier applies to induction matrices; module_options applies to matrices and AE storage/crafting clusters");
        }
        Builder b = new Builder(id);
        switch (id) {
            case "create:press_station", "create:press_basin_station" -> press(b, id.endsWith("basin_station"));
            case "create:mixer_station" -> mixer(b);
            case "ae2:storage_cluster", "ae2:crafting_cluster" -> aeCluster(b, id.equals("ae2:crafting_cluster"), component, registry);
            case "mekanism:induction_matrix" -> matrix(b, component, registry);
            default -> throw new IllegalArgumentException("unsupported machine module: " + id);
        }
        Module result = b.finish();
        if (!component.get("block_id").getAsString().equals(result.coreBlock)) {
            throw new IllegalArgumentException(id + " requires core block_id " + result.coreBlock);
        }
        return result;
    }

    private static void press(Builder b, boolean basin) {
        b.core = "create:mechanical_press";
        String receiver = basin ? "create:basin" : "create:depot";
        b.block(0, 0, 0, receiver, Map.of());
        b.block(0, 2, 0, b.core, Map.of("facing", "east"));
        // RotationPropagator.isLargeToLargeGear: perpendicular X/Y axes and a (1,1,0) offset.
        // This converts the press's horizontal shaft to a vertical external input with real cogwheels.
        b.block(1, 2, 0, "create:large_cogwheel", Map.of("axis", "x"));
        b.block(2, 3, 0, "create:large_cogwheel", Map.of("axis", "y"));
        b.clear.add(new Pos(0, 1, 0));
        b.port("items", false, receiver, 0, 0, 0, Side.WEST, Side.SOUTH, Side.DOWN);
        b.port("items", true, receiver, 0, 0, 0, Side.EAST, Side.SOUTH, Side.DOWN);
        if (basin) {
            b.port("fluids", false, receiver, 0, 0, 0, Side.WEST, Side.SOUTH, Side.DOWN);
            b.port("fluids", true, receiver, 0, 0, 0, Side.EAST, Side.SOUTH, Side.DOWN);
        }
        b.port("kinetic", false, "create:large_cogwheel", 2, 3, 0, Side.UP);
        b.note("PressingBehaviour/DepotBehaviour and BasinOperatingBlockEntity use the receiver two blocks below the press; keep the intervening processing space empty. Verify installed pressing/compacting recipe and finished-output extraction before sustained operation.");
    }

    private static void mixer(Builder b) {
        b.core = "create:mechanical_mixer";
        b.block(0, 0, 0, "create:basin", Map.of());
        b.block(0, 2, 0, b.core, Map.of());
        // MechanicalMixerBlock is a small cogwheel with a Y axis, not a shaft endpoint.
        // RotationPropagator connects adjacent parallel small cogs at a 1:1 opposite rotation.
        b.block(1, 2, 0, "create:cogwheel", Map.of("axis", "y"));
        b.clear.add(new Pos(0, 1, 0));
        for (String medium : List.of("items", "fluids")) {
            b.port(medium, false, "create:basin", 0, 0, 0, Side.WEST, Side.SOUTH, Side.DOWN);
            b.port(medium, true, "create:basin", 0, 0, 0, Side.EAST, Side.SOUTH, Side.DOWN);
        }
        b.port("kinetic", false, "create:cogwheel", 1, 2, 0, Side.UP);
        b.note("BasinOperatingBlockEntity requires a basin two blocks below. Verify minimum mixer speed and recipe heat requirements; this unheated station must not claim heated or superheated recipes without a separately modeled heat source.");
    }

    private static void aeCluster(Builder b, boolean crafting, JsonObject component, SemanticMachineLayout.Registry registry) {
        JsonObject options = component.has("module_options") ? component.getAsJsonObject("module_options") : new JsonObject();
        for (String key : options.keySet()) if (!Set.of("storage_tier", "storage_cells").contains(key)) throw new IllegalArgumentException("unsupported AE module option: " + key);
        String tier = options.has("storage_tier") ? options.get("storage_tier").getAsString() : "1k";
        if (!Set.of("1k", "4k", "16k", "64k", "256k").contains(tier)) throw new IllegalArgumentException("AE storage_tier must be 1k, 4k, 16k, 64k or 256k");
        int count = options.has("storage_cells") ? options.get("storage_cells").getAsInt() : 1;
        if (count < 1 || count > 10) throw new IllegalArgumentException("AE drive supports 1..10 installed storage cells");
        String cellItem = "ae2:item_storage_cell_" + tier;
        if (!registry.itemExists(cellItem)) throw new IllegalArgumentException("AE storage cell is not installed: " + cellItem);
        b.core = crafting ? "ae2:pattern_provider" : "ae2:drive";
        b.block(0, 0, 0, b.core, crafting ? Map.of() : Map.of("facing", "north"));
        b.part(0, 0, 1, MachineLayoutAeNetworks.DENSE, "center");
        b.part(0, 0, 2, MachineLayoutAeNetworks.DENSE, "center");
        b.part(1, 0, 2, MachineLayoutAeNetworks.DENSE, "center");
        b.part(0, 1, 1, "ae2:fluix_glass_cable", "center");
        b.part(0, 1, 1, crafting ? "ae2:pattern_encoding_terminal" : "ae2:terminal", "north");
        b.block(-2, 0, 1, "ae2:energy_acceptor", Map.of());
        b.block(-1, 0, 1, "ae2:controller", Map.of());
        b.block(0, 0, 3, "ae2:interface", Map.of());
        if (crafting) {
            b.block(1, 0, 0, "ae2:molecular_assembler", Map.of());
            b.block(1, 0, 1, "ae2:drive", Map.of("facing", "north"));
            b.block(-1, 0, 2, "ae2:1k_crafting_storage", Map.of());
        }
        JsonObject contents = new JsonObject(); contents.add("offset", SemanticMachineLayout.position(crafting ? new Pos(1,0,1) : new Pos(0,0,0)));
        contents.addProperty("item_id", cellItem); contents.addProperty("count", count); contents.addProperty("role", "storage_cell"); b.initialContents.add(contents);
        b.clear.add(new Pos(0, 1, 0));
        b.port("ae_network", false, MachineLayoutAeNetworks.DENSE, 1, 0, 2, Side.UP);
        b.port("ae_network", true, MachineLayoutAeNetworks.DENSE, 1, 0, 2, Side.UP);
        b.port("energy", false, "ae2:energy_acceptor", -2, 0, 1, Side.UP, Side.DOWN);
        for (String medium : List.of("items", "fluids")) {
            b.port(medium, false, "ae2:interface", 0, 0, 3, Side.SOUTH, Side.UP, Side.DOWN);
            b.port(medium, true, "ae2:interface", 0, 0, 3, Side.SOUTH, Side.UP, Side.DOWN);
        }
        b.note(crafting
                ? "Pattern provider directly touches its molecular assembler; a one-block crafting-storage CPU, drive, interface, controller and encoding terminal share a physical network. Verify the planned storage-cell deposit, encode recipe-backed crafting patterns, insert them through the provider menu, verify channels and submit a measured craft before reporting automation."
                : "Drive rear, controller, energy acceptor, interface and terminal are physically connected. Verify the planned storage-cell deposit, configure interface stocking and verify powered channels plus actual storage transactions.");
    }

    private static void matrix(Builder b, JsonObject component, SemanticMachineLayout.Registry registry) {
        b.core = "mekanism:induction_casing";
        String tier = component.has("module_tier") ? component.get("module_tier").getAsString() : "basic";
        JsonObject options = component.has("module_options") ? component.getAsJsonObject("module_options") : new JsonObject();
        JsonObject template = MekanismMatrixTemplate.compile(tier, options, registry::blockExists, registry::itemExists);
        for (JsonElement e : template.getAsJsonArray("blocks")) {
            MachineLayoutRouting.checkpoint();
            JsonObject row = e.getAsJsonObject(); Pos p = from(row.getAsJsonArray("offset"));
            if (row.get("block_id").getAsString().equals("minecraft:air")) { b.clear.add(p); continue; }
            b.block(p.x(), p.y(), p.z(), row.get("block_id").getAsString(), Map.of());
        }
        b.configurations.addAll(template.getAsJsonArray("configurations"));
        for (JsonElement e : b.configurations) {
            JsonObject c = e.getAsJsonObject(); Pos p = from(c.getAsJsonArray("offset"));
            b.port("energy", c.get("mode").getAsString().equals("output"), "mekanism:induction_port",
                    p.x(), p.y(), p.z(), Side.valueOf(c.get("face").getAsString().toUpperCase(java.util.Locale.ROOT)));
        }
        if (template.has("clearance_cells")) for (JsonElement e : template.getAsJsonArray("clearance_cells")) b.clear.add(from(e.getAsJsonArray()));
        b.commissioning = template.getAsJsonObject("commissioning").deepCopy();
        JsonObject seal = new JsonObject(); JsonArray openings = new JsonArray();
        openings.add(SemanticMachineLayout.position(new Pos(0,1,1))); openings.add(SemanticMachineLayout.position(new Pos(0,2,1)));
        seal.add("offsets", openings); seal.add("outside_offset", SemanticMachineLayout.position(new Pos(-2,0,1))); b.seals.add(seal);
        for (int x : List.of(-2,-1)) { b.clear.add(new Pos(x,0,1)); b.clear.add(new Pos(x,1,1)); }
        b.note("Build every casing edge and approved interior cell/provider, set native induction port input/output modes, then require formed multiblock and observed energy transfer.");
    }

    static Pos from(JsonArray a) { return new Pos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()); }
    private static final class Builder {
        final String id;
        String core;
        final List<Cell> cells = new ArrayList<>();
        final Map<String, Port> ports = new LinkedHashMap<>();
        final Set<Pos> clear = new LinkedHashSet<>();
        final JsonArray configurations = new JsonArray();
        final JsonArray initialContents = new JsonArray();
        final JsonArray seals = new JsonArray();
        JsonObject commissioning = new JsonObject();
        Builder(String id) { this.id = id; }
        void block(int x, int y, int z, String block, Map<String, String> properties) { cells.add(new Cell(new Pos(x,y,z), block, properties, false, "module")); }
        void part(int x, int y, int z, String item, String side) { cells.add(new Cell(new Pos(x,y,z), item, Map.of(), side, "module")); }
        void port(String medium, boolean output, String block, int x, int y, int z, Side... sides) { ports.put(medium + (output ? ":out" : ":in"), new Port(new Pos(x,y,z), block, List.of(sides))); }
        void note(String text) { commissioning.addProperty("requirements", text); commissioning.addProperty("production_verified", false); }
        Module finish() {
            clear.add(new Pos(0, 0, -1)); clear.add(new Pos(0, 1, -1));
            int minX = 0, maxX = 0, minZ = -1, maxZ = 0;
            for (Cell c : cells) { minX = Math.min(minX,c.position().x()); maxX = Math.max(maxX,c.position().x()); minZ = Math.min(minZ,c.position().z()); maxZ = Math.max(maxZ,c.position().z()); }
            Pos shift = new Pos(-(minX + maxX) / 2, 0, -(minZ + maxZ) / 2);
            List<Cell> shifted = cells.stream().map(c -> new Cell(c.position().plus(shift), c.id(), c.properties(), c.part(), c.owner())).toList();
            Map<String, Port> shiftedPorts = new LinkedHashMap<>(); ports.forEach((k,p) -> shiftedPorts.put(k,new Port(p.offset.plus(shift),p.blockId,p.sides)));
            Set<Pos> shiftedClear = new LinkedHashSet<>(); clear.forEach(p -> shiftedClear.add(p.plus(shift)));
            for (JsonElement e : configurations) { JsonObject c = e.getAsJsonObject(); c.add("offset", SemanticMachineLayout.position(from(c.getAsJsonArray("offset")).plus(shift))); }
            for (JsonElement e : initialContents) { JsonObject c = e.getAsJsonObject(); c.add("offset", SemanticMachineLayout.position(from(c.getAsJsonArray("offset")).plus(shift))); }
            for (JsonElement e : seals) {
                JsonObject seal = e.getAsJsonObject(); JsonArray openings = new JsonArray();
                seal.getAsJsonArray("offsets").forEach(p -> openings.add(SemanticMachineLayout.position(from(p.getAsJsonArray()).plus(shift))));
                seal.add("offsets", openings); seal.add("outside_offset", SemanticMachineLayout.position(from(seal.getAsJsonArray("outside_offset")).plus(shift)));
            }
            for (String key : List.of("min_offset", "max_offset")) if (commissioning.has(key)) commissioning.add(key, SemanticMachineLayout.position(from(commissioning.getAsJsonArray(key)).plus(shift)));
            return new Module(id, core, shifted, Map.copyOf(shiftedPorts), Set.copyOf(shiftedClear), configurations, initialContents, seals, commissioning);
        }
    }
}
