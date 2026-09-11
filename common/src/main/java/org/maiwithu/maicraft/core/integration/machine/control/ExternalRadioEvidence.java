package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;

/** Reports observed shared radio channels beyond the selected hull instead of treating them as private wires. */
final class ExternalRadioEvidence {
    private ExternalRadioEvidence() {}
    static void inspect(LocalPlayer player,MachineControlInspection.Observation observation,List<Link> internal) {
        if(internal.isEmpty()) return;
        var graph=observation.circuit();
        try {
            double range=((Number)call(field(field(call(type("com.simibubi.create.infrastructure.config.AllConfigs"),"server"),"logistics"),"linkRange"),"get")).doubleValue();
            int rings=Math.min(16,(int)Math.ceil(range/16)+1);
            Vec3 focus=observation.structure()==null || observation.structure().worldBounds()==null
                    ? player.position():observation.structure().worldBounds().getCenter();
            int cx=(int)Math.floor(focus.x/16),cz=(int)Math.floor(focus.z/16);
            var chunks=new ArrayList<LevelChunk>();
            for(int ring=0;ring<=rings;ring++) for(int dx=-ring;dx<=ring;dx++) for(int dz=-ring;dz<=ring;dz++) {
                if(Math.max(Math.abs(dx),Math.abs(dz))!=ring) continue;
                var chunk=player.clientLevel.getChunkSource().getChunkNow(cx+dx,cz+dz); if(chunk!=null) chunks.add(chunk);
            }
            var frame=SableStructureBridge.open(player.clientLevel,focus,null);
            for(var structure:frame.structures()) if(!structure.id().equals(observation.structureId())) chunks.addAll(structure.loadedChunks());
            int visited=0; var seen=new HashSet<BlockPos>();
            for(LevelChunk chunk:chunks) for(var entry:chunk.getBlockEntities().entrySet()) {
                if(!seen.add(entry.getKey()) || observation.cells().containsKey(entry.getKey())) continue;
                if(++visited>4096) { graph.unknown("external radio observation budget exhausted"); return; }
                Object be=entry.getValue();
                if(!is(be,CREATE+"redstone.link.RedstoneLinkBlockEntity") && !is(be,SIM+"redstone.AbstractLinkedReceiverBlockEntity")
                        && !is(be,SIM+"redstone.linked_typewriter.LinkedTypewriterBlockEntity")) continue;
                Cell cell=new Cell(entry.getKey(),entry.getValue().getBlockState(),entry.getValue());
                var remoteGraph=new ControlCircuit();
                for(Link remote:read(player.level(),cell,remoteGraph)) for(Link local:internal) {
                    if(local.receiver()==remote.receiver() || !local.frequency().equals(remote.frequency())) continue;
                    BlockPos localPos=(BlockPos)call(local.nativeLink(),"getLocation");
                    if(project(player,cell.pos(),!frame.state().equals("not_installed")).distanceTo(observation.world(localPos))>=range) continue;
                    String id="external/"+remote.node();
                    if(graph.node(id)==null) {
                        var facts=new java.util.LinkedHashMap<>(remoteGraph.node(remote.node()).facts()); facts.put("external_to_survey",true);
                        graph.add(new ControlCircuit.Node(id,remote.receiver()?ControlCircuit.Kind.RECEIVER:ControlCircuit.Kind.OTHER,facts));
                    }
                    if(remote.receiver()) graph.connect(new ControlCircuit.Edge(local.node(),id,"wireless","external_receiver_actuation_not_surveyed",false));
                    else graph.unknown("shared receiver "+local.node()+" also matches observed external transmitter "+id);
                }
            }
        } catch(RuntimeException | LinkageError unavailable) { graph.unknown("external radio evidence unavailable: "+unavailable.getClass().getSimpleName()); }
    }
    private static Vec3 project(LocalPlayer player,BlockPos pos,boolean physics) {
        if(physics) {
            Object helper=field(type("dev.ryanhcode.sable.Sable"),"HELPER");
            Vector3dc value=(Vector3dc)call(helper,"projectOutOfSubLevel",player.level(),new Vector3d(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5),new Vector3d());
            return new Vec3(value.x(),value.y(),value.z());
        }
        return Vec3.atCenterOf(pos);
    }
}
