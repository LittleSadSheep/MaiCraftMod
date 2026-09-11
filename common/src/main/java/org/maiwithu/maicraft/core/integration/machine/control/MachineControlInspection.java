package org.maiwithu.maicraft.core.integration.machine.control;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;

/** Inspect the actual control circuit in fixed-world or physical-structure coordinates. */
public final class MachineControlInspection {
    private static final int MAX_CELLS=32768, MAX_COMPONENTS=2048;
    public record Observation(UUID structureId, Map<BlockPos,Cell> cells, ControlCircuit circuit,
                              SableStructureBridge.Structure structure, boolean complete) {
        public Observation { cells=Map.copyOf(cells); }
        public Vec3 world(BlockPos pos) { return structure==null ? Vec3.atCenterOf(pos) : structure.pose().toWorld(Vec3.atCenterOf(pos)); }
        public Cell cell(String id) {
            String base=id.split("/",2)[0];
            return cells.values().stream().filter(c->c.id().equals(base)).findFirst().orElseThrow();
        }
        public JsonObject report() {
            Gson gson=new Gson(); JsonObject out=new JsonObject();
            out.addProperty("schema_version",1); out.addProperty("read_only",true);
            out.addProperty("scope",structureId==null ? "surveyed_world_volume" : "selected_physical_structure");
            if(structureId!=null) out.addProperty("structure_id",structureId.toString());
            out.addProperty("complete",complete && circuit.analyze().complete());
            out.add("components",gson.toJsonTree(circuit.nodes().stream().filter(n->n.kind()!=ControlCircuit.Kind.OTHER).toList()));
            out.add("connections",gson.toJsonTree(circuit.edges())); out.add("analysis",gson.toJsonTree(circuit.analyze()));
            out.addProperty("vehicle_classification",!complete ? "unknown_incomplete_observation"
                    : !circuit.analyze().locomotionObserved() ? "no_locomotion_mechanism_observed"
                    : circuit.analyze().controllableCandidate() ? "control_connected_candidate" : "no_proven_control_path");
            out.addProperty("coordinate_rule","storage_position identifies native blocks; project through the current structure pose for interaction, never walk to storage coordinates");
            out.addProperty("driving_rule","a control-connected candidate still requires an accessible unoccupied seat, a neutral control state, and observed motion response; force and stability analysis are separate");
            return out;
        }
    }
    private MachineControlInspection() {}
    public static Observation world(LocalPlayer player,BlockPos center,int radius) {
        return capture(player,null,center.offset(-radius,-radius,-radius),center.offset(radius,radius,radius));
    }
    public static Observation structure(LocalPlayer player,UUID id) {
        var structure=SableStructureBridge.find(player.clientLevel,id);
        if(structure==null || structure.pose()==null || structure.storageBounds()==null || !Boolean.TRUE.equals(structure.ready()))
            throw new IllegalArgumentException("physical_structure_unavailable: re-observe the selected structure");
        var bounds=structure.storageBounds();
        return capture(player,structure,BlockPos.containing(bounds.minX,bounds.minY,bounds.minZ),
                BlockPos.containing(Math.ceil(bounds.maxX)-1,Math.ceil(bounds.maxY)-1,Math.ceil(bounds.maxZ)-1));
    }
    private static Observation capture(LocalPlayer player,SableStructureBridge.Structure structure,BlockPos min,BlockPos max) {
        var cells=new LinkedHashMap<BlockPos,Cell>(); var circuit=new ControlCircuit();
        List<Link> links=new ArrayList<>(); boolean complete=true; int visited=0;
        outer: for(int y=min.getY();y<=max.getY();y++) for(int z=min.getZ();z<=max.getZ();z++) for(int x=min.getX();x<=max.getX();x++) {
            if(++visited>MAX_CELLS || cells.size()>=MAX_COMPONENTS) { complete=false; circuit.unknown("control survey budget exhausted"); break outer; }
            BlockPos pos=new BlockPos(x,y,z);
            boolean loaded=structure==null ? player.level().isLoaded(pos) : structure.isLoaded(pos);
            if(!loaded) { complete=false; continue; }
            var state=structure==null ? player.level().getBlockState(pos) : structure.readBlock(pos).blockState();
            if(state==null) { complete=false; continue; }
            if(state.isAir()) continue;
            var entity=state.hasBlockEntity() ? player.level().getBlockEntity(pos) : null;
            if(state.hasBlockEntity() && entity==null) { complete=false; circuit.unknown("block entity missing at "+pos.toShortString()); }
            Cell cell=new Cell(pos,state,entity); cells.put(pos,cell);
        }
        if(!complete) circuit.unknown("unloaded, missing or omitted cells; absence does not prove no wiring");
        for(Cell cell:cells.values()) links.addAll(read(player.level(),cell,circuit));
        var observation=new Observation(structure==null ? null:structure.id(),cells,circuit,structure,complete);
        ControlConnections.connect(player.level(),cells,links,observation::world,circuit);
        return observation;
    }
}
