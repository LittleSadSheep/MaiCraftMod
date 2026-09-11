package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;

/** Uses native frequency identity and kinetic ports; physical coordinates are projected before radio tests. */
public final class ControlConnections {
    private ControlConnections() {}
    public static void connect(Level level,Map<BlockPos,Cell> cells,List<Link> links,
                               Function<BlockPos,Vec3> world,ControlCircuit circuit) {
        ControlSignals.connect(level,cells,circuit);
        for(Link source:links) if(!source.receiver()) for(Link target:links) if(target.receiver()
                && source.frequency().equals(target.frequency())) {
            try {
                BlockPos from=(BlockPos)call(source.nativeLink(),"getLocation"), to=(BlockPos)call(target.nativeLink(),"getLocation");
                Cell receiver=cells.get(to);
                double range=((Number)call(field(field(call(type("com.simibubi.create.infrastructure.config.AllConfigs"),"server"),"logistics"),"linkRange"),"get")).doubleValue();
                var transfer=WirelessControlRules.plain(true,world.apply(from),world.apply(to),range,15);
                if(is(receiver.entity(),SIM+"redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity")) {
                    transfer=WirelessControlRules.modulating(true,world.apply(from),world.apply(to),
                            ((Number)field(receiver.entity(),"minRange")).intValue(),((Number)field(receiver.entity(),"maxRange")).intValue(),15);
                } else if(is(receiver.entity(),SIM+"redstone.directional_receiver.DirectionalLinkedReceiverBlockEntity")) {
                    Direction facing=ControlSignals.facing(receiver.state());
                    Vec3 normal=world.apply(to.relative(facing)).subtract(world.apply(to));
                    transfer=WirelessControlRules.directional(true,world.apply(from),world.apply(to),normal,range,15);
                }
                if(transfer.inRange() && transfer.strength()>0) circuit.connect(new ControlCircuit.Edge(source.node(),target.node(),"wireless",
                        transfer.behavior()+"/strength_at_full_input="+transfer.strength(),true));
            } catch(RuntimeException | LinkageError failure) { circuit.unknown("radio evidence unavailable: "+source.node()+" -> "+target.node()); }
        }
        ControlKineticPorts.connect(level,cells,circuit);
    }
}
