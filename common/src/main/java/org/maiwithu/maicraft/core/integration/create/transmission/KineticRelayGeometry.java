// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Elevated real chain-conveyor wheels on filled shaft columns, with native-cost links and terminal turns. */
final class KineticRelayGeometry {
    private record Terminal(Endpoint endpoint, Direction face, BlockPos base) {
        boolean existingWheel() { return face == null; }
    }
    private KineticRelayGeometry() {}
    static List<Plan> candidates(Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace, Terrain terrain, Limits limits) {
        List<Plan> plans = new ArrayList<>();
        for (int shift : new int[] {3, -3}) {
            Terminal a = terminal(source, sourceFace, shift), b = terminal(target, targetFace, shift);
            for (int corridor = 0; corridor < 3; corridor++) {
                Plan plan = compile(source, sourceFace, target, targetFace, terrain, limits, a, b, corridor);
                if (plan != null) plans.add(plan);
            }
            if (sourceFace != Direction.DOWN && targetFace != Direction.DOWN) break;
        }
        return List.copyOf(plans);
    }
    private static Terminal terminal(Endpoint endpoint, Direction face, int shift) {
        if (face == null || face == Direction.UP) return new Terminal(endpoint, face, endpoint.position());
        if (face == Direction.DOWN) return new Terminal(endpoint, face, endpoint.position().below().offset(shift, 0, 0));
        return new Terminal(endpoint, face, endpoint.position().relative(face, 2));
    }
    private static Plan compile(Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace,
            Terrain terrain, Limits limits, Terminal a, Terminal b, int corridor) {
        KineticGeometryWork work = new KineticGeometryWork(source, sourceFace, target, targetFace, terrain, limits);
        List<BlockPos> corners = new ArrayList<>(); corners.add(a.base);
        if (corridor == 1) corners.add(new BlockPos(a.base.getX(), 0, b.base.getZ()));
        if (corridor == 2) corners.add(new BlockPos(b.base.getX(), 0, a.base.getZ()));
        corners.add(b.base);
        for (int i = corners.size() - 1; i > 0; i--) if (sameColumn(corners.get(i), corners.get(i - 1))) corners.remove(i);
        if (corners.size() < 2) return null;
        int height = Math.max(a.base.getY() + (a.existingWheel() ? 0 : 2), b.base.getY() + (b.existingWheel() ? 0 : 2));
        for (int i = 1; i < corners.size(); i++) for (BlockPos column : columns(corners.get(i - 1), corners.get(i))) {
            KineticRouteGeometry.checkpoint();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (endpointColumn(work, column.getX() + dx, column.getZ() + dz)) continue;
                Integer ground = work.ground(column.getX() + dx, column.getZ() + dz);
                if (ground == null) return null;
                height = Math.max(height, ground + limits.clearance() + 1);
            }
        }
        List<BlockPos> wheels = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++) {
            BlockPos p = corners.get(i); int y = i == 0 && a.existingWheel() ? a.endpoint.position().getY()
                    : i == corners.size() - 1 && b.existingWheel() ? b.endpoint.position().getY() : height;
            corners.set(i, new BlockPos(p.getX(), y, p.getZ()));
        }
        wheels.add(corners.getFirst());
        for (int i = 1; i < corners.size(); i++) {
            List<BlockPos> section = split(corners.get(i - 1), corners.get(i), height, limits);
            if (section == null) return null;
            wheels.addAll(section.subList(1, section.size()));
        }
        if (new LinkedHashSet<>(wheels).size() != wheels.size() || wheels.size() > limits.maxPlacements()) return null;
        for (int i = 0; i < wheels.size(); i++) {
            BlockPos wheel = wheels.get(i);
            Terminal terminal = i == 0 ? a : i == wheels.size() - 1 ? b : null;
            if (terminal != null && terminal.existingWheel()) continue;
            work.put(wheel, "create:chain_conveyor", Map.of());
            Integer ground = work.ground(wheel.getX(), wheel.getZ()); if (ground == null) return null;
            int bottom = terminal != null && terminal.face == Direction.UP ? terminal.endpoint.position().getY() + 1 : ground + 1;
            if (wheel.getY() <= bottom || wheel.getY() - bottom > limits.maxSpan()) return null;
            if (terminal != null) attach(work, terminal, wheel);
            for (int y = bottom; y < wheel.getY(); y++) {
                BlockPos shaft = new BlockPos(wheel.getX(), y, wheel.getZ());
                if (!work.blocks.containsKey(shaft)) work.put(shaft, "create:shaft", Map.of("axis", "y"));
                work.join(shaft, shaft.above());
            }
            if (terminal != null && terminal.face == Direction.UP) work.join(terminal.endpoint.position(), terminal.endpoint.position().above());
        }
        if (!work.valid || !KineticChainClearance.clear(work, wheels)) return null;
        for (int i = 1; i < wheels.size(); i++) if (!work.link(wheels.get(i - 1), wheels.get(i))) return null;
        return work.finish("elevated_chain_conveyor");
    }
    private static void attach(KineticGeometryWork work, Terminal terminal, BlockPos wheel) {
        if (terminal.face == Direction.UP) return;
        var path = new ArrayList<BlockPos>(); path.add(terminal.endpoint.position()); path.add(terminal.endpoint.position().relative(terminal.face));
        KineticShaftGeometry.append(path, terminal.base, KineticShaftGeometry.ORDERS.getFirst(), work.limits.maxPlacements());
        path.add(terminal.base.above());
        if (terminal.base.above().getY() >= wheel.getY()) { work.valid = false; return; }
        KineticShaftGeometry.installPath(work, path);
    }
    private static List<BlockPos> split(BlockPos a, BlockPos b, int height, Limits limits) {
        if (KineticRouteGeometry.validLink(a, b, limits.maxChainSpan())) return List.of(a, b);
        double horizontal = Math.hypot((double) a.getX() - b.getX(), (double) a.getZ() - b.getZ());
        int first = Math.max(2, (int) Math.ceil(Math.sqrt(a.distSqr(b)) / (limits.maxChainSpan() - 1.0)));
        int maximum = Math.min(limits.maxPlacements(), (int) (horizontal / 2.5));
        for (int count = first; count <= maximum; count++) {
            List<BlockPos> section = new ArrayList<>(); section.add(a); boolean valid = true;
            for (int i = 1; i < count; i++) section.add(new BlockPos(
                    (int) Math.round(a.getX() + (double) (b.getX() - a.getX()) * i / count), height,
                    (int) Math.round(a.getZ() + (double) (b.getZ() - a.getZ()) * i / count)));
            section.add(b);
            for (int i = 1; i < section.size(); i++) if (!KineticRouteGeometry.validLink(section.get(i - 1), section.get(i), limits.maxChainSpan())) { valid = false; break; }
            if (valid) return section;
        }
        return null;
    }
    private static boolean endpointColumn(KineticGeometryWork work, int x, int z) {
        return work.source.position().getX() == x && work.source.position().getZ() == z
                || work.target.position().getX() == x && work.target.position().getZ() == z;
    }
    private static boolean sameColumn(BlockPos a, BlockPos b) { return a.getX() == b.getX() && a.getZ() == b.getZ(); }
    private static List<BlockPos> columns(BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())) * 2;
        if (steps == 0) return List.of(new BlockPos(a.getX(), 0, a.getZ()));
        var columns = new LinkedHashSet<BlockPos>();
        for (int step = 0; step <= steps; step++) columns.add(new BlockPos(
                (int) Math.round(a.getX() + (double) (b.getX() - a.getX()) * step / steps), 0,
                (int) Math.round(a.getZ() + (double) (b.getZ() - a.getZ()) * step / steps)));
        return List.copyOf(columns);
    }
}
