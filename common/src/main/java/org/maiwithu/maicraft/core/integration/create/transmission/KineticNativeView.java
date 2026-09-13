// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Loaded synchronized native facts select candidates; server evidence is checked before enhanced work. */
final class KineticNativeView {
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String ROTATE = "com.simibubi.create.content.kinetics.base.IRotate";
    record Observation(KineticRouteGeometry.Endpoint endpoint, String blockId, double rpm, boolean powered,String networkId) {}
    private KineticNativeView() {}
    static Observation read(Level level, BlockPos at, Direction exactFace, boolean chainInterface) {
        var values = variants(level,at,exactFace,chainInterface);
        return values.isEmpty() ? null : values.getFirst();
    }
    static java.util.List<Observation> variants(Level level, BlockPos at, Direction exactFace, boolean chainInterface) {
        if (!level.isLoaded(at)) return java.util.List.of();
        var state = level.getBlockState(at); var entity = level.getBlockEntity(at);
        if (!NativeApi.is(entity,KINETIC) || !NativeApi.is(state.getBlock(),ROTATE)) return java.util.List.of();
        try {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            String family = id.equals("create:chain_conveyor") ? "chain_conveyor" : id.equals("create:large_cogwheel")
                    ? "large_cogwheel" : id.equals("create:cogwheel") ? "cogwheel" : "shaft";
            var axis = (Direction.Axis) NativeApi.call(state.getBlock(),ROTATE,"getRotationAxis",state);
            var faces = new ArrayList<Direction>();
            if (!family.equals("chain_conveyor") || !chainInterface || exactFace != null)
                for (Direction side : Direction.values()) if ((exactFace == null || side == exactFace)
                        && NativeApi.truth(NativeApi.call(state.getBlock(),ROTATE,"hasShaftTowards",level,at,state,side))) faces.add(side);
            if (faces.isEmpty() && !(family.equals("chain_conveyor") && chainInterface && exactFace == null)) return java.util.List.of();
            double speed = ((Number) NativeApi.call(entity,KINETIC,"getSpeed")).doubleValue();
            boolean powered = Double.isFinite(speed) && Math.abs(speed) > .0001
                    && NativeApi.truth(NativeApi.call(entity,KINETIC,"hasNetwork"))
                    && !NativeApi.truth(NativeApi.call(entity,KINETIC,"isOverStressed"));
            var result = new java.util.ArrayList<Observation>();
            Object network=NativeApi.field(entity,KINETIC,"network");String networkId=network==null?"":network.toString();
            if (faces.isEmpty()) result.add(new Observation(new KineticRouteGeometry.Endpoint(at,axis,faces,family),id,speed,powered,networkId));
            else for (Direction.Axis portAxis : Direction.Axis.values()) {
                var onAxis = faces.stream().filter(face -> face.getAxis() == portAxis).toList();
                if (!onAxis.isEmpty()) result.add(new Observation(new KineticRouteGeometry.Endpoint(at,portAxis,onAxis,family),id,speed,powered,networkId));
            }
            return java.util.List.copyOf(result);
        } catch (RuntimeException unavailable) { return java.util.List.of(); }
    }
    static boolean kinetic(Level level,BlockPos at) {
        return level.isLoaded(at) && NativeApi.is(level.getBlockState(at).getBlock(),ROTATE);
    }
}
