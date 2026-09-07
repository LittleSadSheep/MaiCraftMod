// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge.Structure;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** Read-only physical-sublevel observation; block storage positions never masquerade as world targets. */
final class PhysicalStructurePerception {
    private static final int RANGE = 128;
    private PhysicalStructurePerception() {}

    static JsonObject view(LocalPlayer player) {
        JsonObject out = new JsonObject();
        out.add("eye_position", StructurePerceptionJson.vector(player.getEyePosition()));
        out.add("direction", StructurePerceptionJson.vector(player.getViewVector(1F)));
        out.addProperty("yaw", player.getYRot()); out.addProperty("pitch", player.getXRot());
        return out;
    }

    static JsonObject observe(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit;
        try {
            // Sable's own Level.clip transforms the ray into sublevels and compares occlusion.
            hit = player.level().clip(new ClipContext(eye, eye.add(player.getViewVector(1F).scale(RANGE)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        } catch (RuntimeException | LinkageError failure) {
            return StructurePerceptionJson.state("unknown", "native view ray unavailable: " + failure.getClass().getSimpleName());
        }
        var frame = SableStructureBridge.openForPerception(player.clientLevel, eye,
                hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null);
        JsonObject out = new JsonObject();
        out.addProperty("engine", "sable"); out.addProperty("state", frame.state());
        if (frame.error() != null) out.addProperty("error", frame.error());
        out.addProperty("observed_game_tick", player.level().getGameTime());
        out.addProperty("dimension", player.level().dimension().location().toString());
        out.addProperty("range", RANGE); out.addProperty("read_only", true);
        out.addProperty("total_loaded_structures", frame.total());
        out.addProperty("omitted_structures", frame.omitted());
        out.addProperty("metadata_probes", frame.metadataProbes());
        out.addProperty("truncated", frame.truncated());
        SableStructureBridge.HitResolution resolution = hit.getType() == HitResult.Type.BLOCK
                ? frame.resolveHit(hit.getBlockPos()) : new SableStructureBridge.HitResolution("miss", null, null);
        Structure looked = resolution.structureId() == null ? null : frame.structures().stream()
                .filter(s -> resolution.structureId().equals(s.id())).findFirst().orElse(null);
        try { out.add("look_target", describeHit(resolution, looked, hit, eye)); }
        catch (RuntimeException | LinkageError invalidHit) {
            JsonObject unknown = StructurePerceptionJson.state("unknown", "native hit conversion failed: " + invalidHit.getClass().getSimpleName());
            if (resolution.structureId() != null) unknown.addProperty("structure_id", resolution.structureId().toString());
            out.add("look_target", unknown);
        }
        JsonArray structures = new JsonArray();
        boolean detailed = false;
        var candidates = new java.util.ArrayList<>(frame.structures());
        java.util.UUID targetId = null;
        var transport = org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.diagnosticState();
        if (Boolean.TRUE.equals(transport.get("active")) && transport.get("moving_target") instanceof java.util.Map<?,?> target) {
            try { targetId = java.util.UUID.fromString(String.valueOf(target.get("structure_id"))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (targetId != null) {
            java.util.UUID wanted = targetId;
            if (candidates.stream().noneMatch(s -> wanted.equals(s.id()))) {
                Structure active = SableStructureBridge.find(player.clientLevel, wanted);
                if (active != null) candidates.add(active);
            }
        }
        var presentation = org.maiwithu.maicraft.core.integration.physics.StructurePresentation.select(
                candidates, resolution.structureId(), targetId, player.getBoundingBox());
        out.addProperty("small_structures_collapsed", presentation.smallCollapsed());
        out.addProperty("other_details_omitted", presentation.otherOmitted());
        for (Structure structure : presentation.shown()) {
            JsonObject row;
            try { row = describe(structure); }
            catch (RuntimeException | LinkageError invalidPose) {
                row = StructurePerceptionJson.state("unknown", "structure description failed: " + invalidPose.getClass().getSimpleName());
                if (structure.id() != null) row.addProperty("structure_id", structure.id().toString());
                structures.add(row); continue;
            }
            boolean selected = looked != null && looked == structure;
            boolean activeTarget = targetId != null && targetId.equals(structure.id());
            row.addProperty("looked_at", selected);
            row.addProperty("boarding_target", activeTarget);
            if ((selected || activeTarget || !detailed && looked == null) && Boolean.TRUE.equals(structure.ready())
                    && structure.pose() != null && structure.storageBounds() != null && structure.plotCenter() != null) {
                try {
                    Vec3 focus = selected ? hit.getLocation() : structure.pose().toStorage(eye);
                    JsonObject deck = PhysicalDeckSampler.sample(player.level(), structure::isLoaded,
                            structure.pose(), structure.storageBounds(), structure.plotCenter(), focus,
                            player.getBbWidth(), player.getBbHeight());
                    for (var element : deck.getAsJsonArray("support_surface_candidates")) {
                        JsonObject candidate = element.getAsJsonObject();
                        JsonObject point = candidate.getAsJsonObject("upright_feet_candidate");
                        double x = point.get("x").getAsDouble(), y = point.get("y").getAsDouble(), z = point.get("z").getAsDouble();
                        var body = new net.minecraft.world.phys.AABB(x - player.getBbWidth() / 2, y, z - player.getBbWidth() / 2,
                                x + player.getBbWidth() / 2, y + player.getBbHeight(), z + player.getBbWidth() / 2);
                        candidate.addProperty("main_world_clearance", mainWorldClearance(body,
                                player.level()::hasChunksAt, () -> player.level().noCollision(player, body)));
                    }
                    row.add("deck_observation", deck);
                } catch (RuntimeException | LinkageError failedGeometry) {
                    row.add("deck_observation", StructurePerceptionJson.state("unknown", "native geometry unavailable: " + failedGeometry.getClass().getSimpleName()));
                }
                detailed = true;
            } else row.add("deck_observation", StructurePerceptionJson.state("not_sampled", "detail is limited to the viewed or nearest structure"));
            structures.add(row);
        }
        out.add("structures", structures);
        out.addProperty("identity_rule", "UUID identifies a physical structure; re-observe its current pose. Missing from this bounded frame does not mean destroyed.");
        out.addProperty("navigation_rule", "use travel structure_id to board an observed vessel; native boarding selects and tracks its local deck. Never use plot storage coordinates as world travel goals. Small structures remain collision obstacles even when collapsed here.");
        return out;
    }

    static JsonObject describeHit(SableStructureBridge.HitResolution resolution, Structure structure,
                                  BlockHitResult hit, Vec3 eye) {
        if (hit.getType() != HitResult.Type.BLOCK) return StructurePerceptionJson.state("miss", "no native block hit within observation range");
        if ("hit".equals(resolution.state())) {
            if (structure == null || structure.pose() == null || structure.plotCenter() == null) {
                JsonObject unknown = StructurePerceptionJson.state("unknown", "viewed structure pose is unavailable");
                if (resolution.structureId() != null) unknown.addProperty("structure_id", resolution.structureId().toString());
                return unknown;
            }
            JsonObject out = StructurePerceptionJson.state("physical_structure", "native sublevel block hit");
            out.addProperty("structure_id", resolution.structureId().toString());
            out.add("storage_block", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(hit.getBlockPos())));
            out.add("local_block", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(hit.getBlockPos().subtract(structure.plotCenter()))));
            out.add("local_hit", StructurePerceptionJson.vector(hit.getLocation().subtract(Vec3.atLowerCornerOf(structure.plotCenter()))));
            Vec3 worldHit = structure.pose().toWorld(hit.getLocation());
            out.add("world_hit", StructurePerceptionJson.vector(worldHit));
            out.add("world_face_normal", StructurePerceptionJson.vector(structure.pose().normalToWorld(Vec3.atLowerCornerOf(hit.getDirection().getNormal()))));
            out.addProperty("distance", eye.distanceTo(worldHit));
            var block = structure.readBlock(hit.getBlockPos());
            out.addProperty("block_state_available", "known".equals(block.state()));
            if (block.blockState() != null) out.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(block.blockState().getBlock()).toString());
            return out;
        }
        if ("not_structure".equals(resolution.state())) {
            JsonObject out = StructurePerceptionJson.state("world_block", "native ray hit the main level");
            out.add("world_hit", StructurePerceptionJson.vector(hit.getLocation()));
            out.add("world_block", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(hit.getBlockPos())));
            return out;
        }
        return StructurePerceptionJson.state(resolution.state(), resolution.error() == null ? "physical hit identity is unavailable" : resolution.error());
    }

    static String mainWorldClearance(net.minecraft.world.phys.AABB body,
                                    java.util.function.BiPredicate<BlockPos, BlockPos> loaded,
                                    java.util.function.BooleanSupplier clear) {
        // BlockCollisions also visits adjacent origins for shapes protruding across chunk edges.
        var origins = body.inflate(1.0000001);
        if (!loaded.test(BlockPos.containing(origins.minX, origins.minY, origins.minZ),
                BlockPos.containing(origins.maxX, origins.maxY, origins.maxZ))) return "unknown_unloaded";
        return clear.getAsBoolean() ? "clear_now" : "obstructed_now";
    }

    static JsonObject describe(Structure structure) {
        JsonObject row = new JsonObject();
        row.addProperty("state", structure.state());
        if (structure.id() != null) row.addProperty("structure_id", structure.id().toString());
        if (structure.name() != null) row.addProperty("name", structure.name());
        if (structure.ready() != null) row.addProperty("finalized", structure.ready());
        if (structure.worldBounds() != null) row.add("world_swept_bounds", StructurePerceptionJson.box(structure.worldBounds()));
        if (structure.storageBounds() != null) row.add("storage_bounds_exclusive_max", StructurePerceptionJson.box(structure.storageBounds()));
        if (structure.plotCenter() != null) row.add("local_origin_storage", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(structure.plotCenter())));
        if (structure.pose() != null) row.add("pose", StructurePerceptionJson.pose(structure.pose()));
        if (structure.pose() != null && structure.lastPose() != null && structure.plotCenter() != null) {
            Vec3 reference = Vec3.atLowerCornerOf(structure.plotCenter());
            Vec3 current = structure.pose().toWorld(reference), before = structure.lastPose().toWorld(reference);
            JsonObject motion = new JsonObject();
            motion.add("reference_point_world", StructurePerceptionJson.vector(current));
            motion.add("linear_blocks_per_second", StructurePerceptionJson.vector(current.subtract(before).scale(20)));
            motion.add("angular_world_radians_per_second", StructurePerceptionJson.vector(angularVelocity(structure.lastPose(), structure.pose())));
            motion.addProperty("source", "client_pose_delta_at_plot_origin; estimate, not server center-of-mass velocity");
            row.add("motion", motion);
        }
        row.addProperty("observed_loaded_chunks", structure.loadedChunks().size());
        JsonObject errors = new JsonObject(); structure.errors().forEach(errors::addProperty); row.add("unknown_fields", errors);
        return row;
    }

    static Vec3 angularVelocity(StructurePose before, StructurePose now) {
        Quaterniond previous = new Quaterniond(before.orientationX(), before.orientationY(), before.orientationZ(), before.orientationW());
        Quaterniond delta = new Quaterniond(now.orientationX(), now.orientationY(), now.orientationZ(), now.orientationW()).mul(previous.conjugate()).normalize();
        if (delta.w < 0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
        double sine = Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z);
        if (sine < 1e-12) return Vec3.ZERO;
        double scale = 2 * Math.atan2(sine, Math.clamp(delta.w, -1, 1)) * 20 / sine;
        return new Vec3(delta.x * scale, delta.y * scale, delta.z * scale);
    }
}
