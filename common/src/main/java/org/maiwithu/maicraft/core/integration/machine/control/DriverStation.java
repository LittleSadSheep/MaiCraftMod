package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Comparator;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;

/** Associates a real seat with reachable controls; proximity to an arbitrary structure is insufficient. */
public record DriverStation(BlockPos seat,List<BlockPos> controls) {
    public DriverStation { seat=seat.immutable(); controls=List.copyOf(controls); }
    public static DriverStation select(LocalPlayer player,MachineControlInspection.Observation observation,VehicleControlPlan plan) {
        List<BlockPos> controls=plan.inputs().stream().map(i->observation.cell(i.id()).pos()).distinct().toList();
        return observation.cells().values().stream().filter(c->kind(c)==ControlCircuit.Kind.SEAT)
                .filter(c->unoccupied(player,c.pos()))
                .filter(c->controls.stream().allMatch(p->c.pos().distSqr(p)<Math.pow(player.blockInteractionRange()-.5,2)))
                .sorted(Comparator.comparingDouble(c->observation.world(c.pos()).distanceToSqr(player.position())))
                .map(c->new DriverStation(c.pos(),controls)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("no unoccupied driver seat within reach of the proven controls"));
    }
    public boolean seated(LocalPlayer player) {
        var vehicle=player.getVehicle();
        return vehicle!=null && ControlReflection.is(vehicle,CREATE+"contraptions.actors.seat.SeatEntity")
                && vehicle.isAlive() && vehicle.hasPassenger(player) && vehicle.blockPosition().equals(seat);
    }
    public static boolean unoccupied(LocalPlayer player,BlockPos seat) {
        for(var entity:player.clientLevel.entitiesForRendering()) {
            if(ControlReflection.is(entity,CREATE+"contraptions.actors.seat.SeatEntity")
                    && entity.blockPosition().equals(seat) && entity.getPassengers().stream().anyMatch(p->p!=player)) return false;
        }
        return true;
    }
    public static Vec3 aim(LocalPlayer player,SableStructureBridge.Structure structure,BlockPos pos) {
        var state=structure.readBlock(pos).blockState();
        if(state==null) return null;
        var shape=state.getShape(player.level(),pos);
        Vec3 local=shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(pos));
        return structure.pose().toWorld(local);
    }
    public static BlockHitResult hit(LocalPlayer player,SableStructureBridge.Structure structure,BlockPos pos) {
        if(!structure.isLoaded(pos)) return null;
        Vec3 eye=player.getEyePosition();
        var hit=player.level().clip(new ClipContext(eye,eye.add(player.getViewVector(1F).scale(player.blockInteractionRange())),
                ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                && structure.pose().toWorld(hit.getLocation()).distanceTo(eye)<=player.blockInteractionRange()+1e-5 ? hit:null;
    }
}
