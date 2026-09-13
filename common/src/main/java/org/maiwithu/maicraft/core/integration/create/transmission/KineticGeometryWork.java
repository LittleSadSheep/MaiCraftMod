// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Keeps material counts tied to actual targets and rejects undeclared kinetic attachments. */
final class KineticGeometryWork {
    final Endpoint source, target;
    final Direction sourceFace, targetFace;
    final Terrain terrain;
    final Limits limits;
    final Map<BlockPos, Placement> blocks = new LinkedHashMap<>();
    final List<ChainLink> links = new ArrayList<>();
    final Set<Set<BlockPos>> joins = new HashSet<>();
    boolean valid = true;
    KineticGeometryWork(Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace, Terrain terrain, Limits limits) {
        this.source = source; this.sourceFace = sourceFace; this.target = target; this.targetFace = targetFace; this.terrain = terrain; this.limits = limits;
    }
    void put(BlockPos at, String id, Map<String, String> state) {
        KineticRouteGeometry.checkpoint();
        if (!valid) return;
        if (at.equals(source.position()) || at.equals(target.position()) || !free(at)) { valid = false; return; }
        Placement placement = new Placement(at, id, state), previous = blocks.putIfAbsent(at.immutable(), placement);
        if (previous != null && !previous.equals(placement) || blocks.size() > limits.maxPlacements()) valid = false;
    }
    void join(BlockPos a, BlockPos b) {
        if (a.equals(b)) { valid = false; return; }
        joins.add(Set.of(a.immutable(), b.immutable()));
    }
    boolean free(BlockPos at) {
        return inEnvelope(at) && terrain.loaded(at) && !terrain.protectedCell(at) && terrain.passable(at) && !terrain.kinetic(at);
    }
    private boolean inEnvelope(BlockPos at) {
        int margin = Math.min(limits.maxSpan(), 32);
        return at.getX() >= Math.min(source.position().getX(), target.position().getX()) - margin
                && at.getX() <= Math.max(source.position().getX(), target.position().getX()) + margin
                && at.getZ() >= Math.min(source.position().getZ(), target.position().getZ()) - margin
                && at.getZ() <= Math.max(source.position().getZ(), target.position().getZ()) + margin
                && at.getY() >= Math.min(source.position().getY(), target.position().getY()) - limits.maxSpan()
                && at.getY() <= Math.max(source.position().getY(), target.position().getY()) + limits.maxSpan();
    }
    Integer ground(int x, int z) {
        BlockPos probe = new BlockPos(x, source.position().getY(), z);
        if (!terrain.loaded(probe)) return null;
        Integer ground = terrain.groundHeight(x, z);
        return ground != null && terrain.loaded(new BlockPos(x, ground, z)) ? ground : null;
    }
    boolean link(BlockPos a, BlockPos b) {
        if (!KineticRouteGeometry.validLink(a, b, limits.maxChainSpan())) return valid = false;
        links.add(new ChainLink(a, b, KineticRouteGeometry.chainCost(a, b))); return true;
    }
    Plan finish(String family) {
        if (!valid || blocks.size() > limits.maxPlacements()) return null;
        for (Set<BlockPos> edge : joins) {
            var iterator = edge.iterator(); BlockPos a = iterator.next(), b = iterator.next();
            Direction face = KineticRouteGeometry.between(a, b);
            if (face == null || !coupled(a, b, face)) return null;
        }
        for (Placement block : blocks.values()) {
            KineticRouteGeometry.checkpoint();
            for (Direction face : Direction.values()) {
                BlockPos neighbor = block.position().relative(face);
                if (blocks.containsKey(neighbor)) {
                    if (coupled(block.position(), neighbor, face)
                            && !joins.contains(Set.of(block.position(), neighbor))) return null;
                    continue;
                }
                if (neighbor.equals(source.position()) || neighbor.equals(target.position())) {
                    if (!joins.contains(Set.of(block.position(), neighbor))) return null;
                    continue;
                }
                if (!terrain.loaded(neighbor) || terrain.kinetic(neighbor)) return null;
            }
        }
        Map<String, Integer> materials = new LinkedHashMap<>();
        blocks.values().forEach(block -> materials.merge(org.maiwithu.maicraft.core.integration.machine.MachinePlacementItems.itemId(block.blockId(), block.properties()), 1, Math::addExact));
        links.forEach(link -> materials.merge("minecraft:chain", link.chains(), Math::addExact));
        return new Plan(family, source, sourceFace, target, targetFace, List.copyOf(blocks.values()), links, materials);
    }
    private boolean coupled(BlockPos a, BlockPos b, Direction face) {
        Placement first = blocks.get(a), second = blocks.get(b);
        if (face.getAxis() != Direction.Axis.Y && first != null && second != null
                && first.blockId().equals("create:encased_chain_drive") && second.blockId().equals(first.blockId())) {
            String alongX = Boolean.toString(face.getAxis() == Direction.Axis.X);
            return "y".equals(first.properties().get("axis")) && "y".equals(second.properties().get("axis"))
                    && alongX.equals(first.properties().get("axis_along_first")) && alongX.equals(second.properties().get("axis_along_first"));
        }
        return exposes(a, face) && exposes(b, face.getOpposite());
    }
    private boolean exposes(BlockPos at, Direction face) {
        if (at.equals(source.position())) return face == sourceFace;
        if (at.equals(target.position())) return face == targetFace;
        Placement block = blocks.get(at); if (block == null) return false;
        if (block.blockId().equals("create:chain_conveyor")) return face == Direction.DOWN;
        String axis = block.properties().get("axis");
        return block.blockId().equals("create:gearbox") ? !face.getAxis().getName().equals(axis)
                : face.getAxis().getName().equals(axis);
    }
}
