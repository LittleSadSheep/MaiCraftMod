// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Bounded, read-only client evidence for an LLM's machine analysis. Call on the game thread.
 * Never loads chunks, opens menus, reads arbitrary NBT, or mutates machines. The structure
 * fingerprint is a stale-observation guard, not proof of ownership or machine configuration.
 */
public final class MachineSurvey {
    public static final int MAX_RADIUS = MachineSurveyModel.MAX_RADIUS;
    public static final int MAX_BLOCKS = 768;
    public static final int MAX_ADJACENCIES = 1024;
    private static final int MAX_PROPERTIES = 32;
    private static final int MAX_PALETTE = 256;
    private static final int MAX_COMPONENT_DETAILS = 256;
    private static final Map<Level, String> WORLD_SCOPES = new WeakHashMap<>();

    private MachineSurvey() {}

    /** Radius is defensively clamped; callers should reject invalid user arguments first. */
    public static JsonObject inspect(LocalPlayer self, BlockPos center, int requestedRadius) {
        Objects.requireNonNull(self, "local player");
        Objects.requireNonNull(center, "machine center");
        Capture capture = capture(self.level(), center, requestedRadius, true);
        JsonObject report = new JsonObject();
        report.addProperty("schema_version", 1);
        report.addProperty("dimension", self.level().dimension().location().toString());
        report.addProperty("world_scope", capture.worldScope);
        report.addProperty("world_scope_semantics", "client level session; expires on disconnect or level replacement");
        report.add("center", coordinates(center.getX(), center.getY(), center.getZ()));
        report.addProperty("radius", capture.radius);
        report.addProperty("requested_radius", requestedRadius);
        report.addProperty("radius_clamped", requestedRadius != capture.radius);
        report.addProperty("observed_game_tick", self.level().getGameTime());
        report.addProperty("structure_fingerprint", capture.fingerprint);
        report.addProperty("fingerprint_scope", "session, dimension, bounded volume, load state, block states, block entity types, readable AE2 part classes; excludes inventories, recipe settings, side configuration, network membership and permissions");
        report.addProperty("read_only", true);
        report.addProperty("evidence_source", "loaded_client_world");
        report.addProperty("authoritative_server_snapshot", false);
        report.addProperty("total_volume", capture.total);
        report.addProperty("loaded_cell_count", capture.loaded);
        report.addProperty("unloaded_cell_count", capture.unloaded);
        report.addProperty("outside_world_cell_count", capture.outside);
        report.addProperty("air_cell_count", capture.air);
        report.addProperty("observed_non_air_blocks", capture.blocks.size());
        report.addProperty("has_relevant_blocks", capture.blocks.stream().anyMatch(block -> block.hint.relevant()));
        JsonArray mods = new JsonArray();
        capture.mods.forEach(mods::add);
        report.add("detected_mods", mods);

        // Prefer machine blocks and nearby useful blocks when a terrain-heavy volume exceeds the budget.
        capture.blocks.sort(Comparator.comparingInt((ObservedBlock block) -> block.hint.relevant() ? 0 : 1)
                .thenComparingInt(block -> distanceSquared(block.relative))
                .thenComparingInt(block -> block.relative.getY())
                .thenComparingInt(block -> block.relative.getZ())
                .thenComparingInt(block -> block.relative.getX()));
        Map<String, Integer> paletteLookup = new LinkedHashMap<>();
        JsonArray palette = new JsonArray();
        JsonArray relativeBlocks = new JsonArray();
        JsonArray details = new JsonArray();
        List<MachineSurveyModel.Component> relevant = new ArrayList<>();
        int omitted = 0;
        int omittedDetails = 0;
        for (ObservedBlock block : capture.blocks) {
            Integer paletteIndex = paletteLookup.get(block.stateKey);
            if (relativeBlocks.size() >= MAX_BLOCKS || (paletteIndex == null && palette.size() >= MAX_PALETTE)) {
                omitted++;
                continue;
            }
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteLookup.put(block.stateKey, paletteIndex);
                JsonObject entry = new JsonObject();
                entry.addProperty("block_id", block.id);
                entry.add("properties", block.properties);
                entry.addProperty("family", block.hint.family());
                JsonArray roles = new JsonArray();
                block.hint.roles().forEach(roles::add);
                entry.add("inferred_roles", roles);
                entry.addProperty("role_basis", "registry_name_heuristic_not_verified_function");
                palette.add(entry);
            }
            int blockIndex = relativeBlocks.size();
            JsonArray row = coordinates(block.relative.getX(), block.relative.getY(), block.relative.getZ());
            row.add(paletteIndex);
            relativeBlocks.add(row);
            if (block.hint.relevant() || block.entityType != null) {
                relevant.add(new MachineSurveyModel.Component(new MachineSurveyModel.Point(
                        block.relative.getX(), block.relative.getY(), block.relative.getZ()), blockIndex));
            }
            if (block.evidence != null) {
                if (details.size() < MAX_COMPONENT_DETAILS) {
                    block.evidence.addProperty("block_index", blockIndex);
                    details.add(block.evidence);
                } else omittedDetails++;
            }
        }
        report.add("palette", palette);
        report.add("relative_blocks", relativeBlocks);
        report.addProperty("relative_blocks_format", "[offset_x,offset_y,offset_z,palette_index]; offsets are from the marked center");
        report.addProperty("unlisted_space", "Unlisted offsets within the cube are air only when structure_complete is true; otherwise they may be unobserved or omitted blocks.");
        report.addProperty("omitted_non_air_blocks", omitted);
        report.add("component_evidence", details);
        report.addProperty("omitted_component_details", omittedDetails);
        report.addProperty("ae2_hosts_with_unknown_parts", capture.unknownAe2Hosts);
        report.addProperty("properties_truncated", capture.propertiesTruncated);

        JsonArray adjacency = new JsonArray();
        MachineSurveyModel.Adjacencies observedAdjacencies = MachineSurveyModel.adjacent(relevant, MAX_ADJACENCIES);
        for (MachineSurveyModel.Edge candidate : observedAdjacencies.edges()) {
            JsonArray edge = new JsonArray();
            edge.add(candidate.fromBlock());
            edge.add(candidate.toBlock());
            edge.add(candidate.face());
            adjacency.add(edge);
        }
        report.add("candidate_adjacencies", adjacency);
        report.addProperty("candidate_adjacencies_format", "[from_block_index,to_block_index,face_from_first]; indices refer to relative_blocks");
        report.addProperty("adjacency_meaning", "observed touching faces only; does not prove compatible ports, transport, power, network membership, recipe or flow direction");
        report.addProperty("omitted_candidate_adjacencies", observedAdjacencies.omitted());
        boolean truncated = omitted > 0 || omittedDetails > 0 || observedAdjacencies.omitted() > 0 || capture.propertiesTruncated;
        report.addProperty("truncated", truncated);
        report.addProperty("complete", capture.unloaded == 0 && capture.outside == 0 && !truncated);
        report.addProperty("structure_complete", capture.unloaded == 0 && capture.outside == 0
                && omitted == 0 && !capture.propertiesTruncated);
        report.addProperty("machine_semantics_complete", false);
        JsonArray unknowns = new JsonArray();
        for (String value : List.of("ownership_and_permission", "server_inventory_and_recipe_state",
                "ae2_channels_storage_and_remote_network", "mekanism_energy_chemicals_and_side_configuration",
                "actual_transport_and_direction", "remote_wireless_or_multiblock_links",
                "machine_parts_outside_the_bounded_volume", "unsynchronized_client_block_entity_fields")) unknowns.add(value);
        report.add("unknowns", unknowns);
        JsonArray rules = new JsonArray();
        for (String rule : List.of("Separate observed facts from hypotheses and cite block indices.",
                "Trace inputs, outputs, power, control and failure points; label uncertain edges.",
                "Request focused follow-up observations or menu evidence before assuming recipes or port configuration.",
                "Re-survey before executing a plan and verify its effect afterward.",
                "A matching structural fingerprint does not validate settings, inventory, ownership or action safety.")) rules.add(rule);
        report.add("analysis_rules", rules);
        return report;
    }

    /** Re-observe the same bounded structure, without transient kinetic telemetry or a full JSON report. */
    public static String fingerprint(LocalPlayer self, BlockPos center, int radius) {
        Objects.requireNonNull(self, "local player");
        Objects.requireNonNull(center, "machine center");
        return capture(self.level(), center, radius, false).fingerprint;
    }

    private static Capture capture(Level level, BlockPos center, int requestedRadius, boolean includeTelemetry) {
        int radius = MachineSurveyModel.boundedRadius(requestedRadius);
        Capture result = new Capture(radius, worldScope(level));
        MessageDigest digest = digest();
        hash(digest, "machine-survey-v1", result.worldScope, level.dimension().location().toString(),
                center.toShortString(), Integer.toString(radius));
        // Fixed traversal includes air and missing cells in the digest, even when the public payload truncates.
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    result.total++;
                    hash(digest, "cell", Integer.toString(dx), Integer.toString(dy), Integer.toString(dz));
                    BlockPos relative = new BlockPos(dx, dy, dz);
                    BlockPos absolute = center.offset(dx, dy, dz);
                    if (level.isOutsideBuildHeight(absolute) || !level.getWorldBorder().isWithinBounds(absolute)) {
                        result.outside++;
                        hash(digest, "outside");
                        continue;
                    }
                    if (!level.isLoaded(absolute)) {
                        result.unloaded++;
                        hash(digest, "unloaded");
                        continue;
                    }
                    result.loaded++;
                    BlockState state = level.getBlockState(absolute);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    JsonObject properties = new JsonObject();
                    List<Property<?>> ordered = new ArrayList<>(state.getProperties());
                    ordered.sort(Comparator.comparing(Property::getName));
                    for (int index = 0; index < ordered.size(); index++) {
                        Property<?> property = ordered.get(index);
                        // Hash every property even if the public representation must be cut short.
                        String value = propertyValue(state, property);
                        hash(digest, property.getName(), value);
                        if (index < MAX_PROPERTIES) properties.addProperty(property.getName(), value);
                        else result.propertiesTruncated = true;
                    }
                    hash(digest, id, "properties_end");
                    if (state.isAir()) { result.air++; continue; }
                    MachineSurveyModel.Hint hint = MachineSurveyModel.classify(id);
                    if (hint.family().equals("create") || hint.family().equals("ae2")
                            || hint.family().equals("mekanism")) result.mods.add(hint.family());
                    BlockEntity entity = state.hasBlockEntity() ? level.getBlockEntity(absolute) : null;
                    String entityType = entity == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
                    hash(digest, entityType == null ? (state.hasBlockEntity() ? "entity_unavailable" : "no_entity") : entityType);
                    JsonObject evidence = null;
                    if (state.hasBlockEntity()) {
                        evidence = new JsonObject();
                        evidence.addProperty("block_entity_present", entity != null);
                        if (entityType != null) evidence.addProperty("block_entity_type", entityType);
                        evidence.addProperty("source", "client_block_state_and_synced_entity");
                    }
                    if (hint.family().equals("ae2") && id.endsWith(":cable_bus")) {
                        JsonObject parts = OptionalReads.ae2Parts(entity);
                        if (parts == null) {
                            result.unknownAe2Hosts++;
                            hash(digest, "ae2_parts_unknown");
                            if (evidence == null) evidence = new JsonObject();
                            evidence.addProperty("ae2_parts_status", "unknown_api_absent_entity_missing_or_read_failed");
                        } else {
                            hash(digest, "ae2_parts", parts.toString());
                            if (evidence == null) evidence = new JsonObject();
                            evidence.add("ae2_part_classes_by_face", parts);
                            evidence.addProperty("ae2_parts_status", "observed_client_classes_not_network_state");
                        }
                    }
                    if (includeTelemetry && entity != null && hint.family().equals("create")) {
                        JsonObject kinetic = OptionalReads.kinetics(entity);
                        if (kinetic != null) {
                            if (evidence == null) evidence = new JsonObject();
                            evidence.add("create_kinetic_client_fields", kinetic);
                        }
                    }
                    result.blocks.add(new ObservedBlock(relative, id, properties, id + properties,
                            hint, entityType, evidence));
                }
            }
        }
        result.fingerprint = HexFormat.of().formatHex(digest.digest());
        return result;
    }

    private static synchronized String worldScope(Level level) {
        return WORLD_SCOPES.computeIfAbsent(level, ignored -> UUID.randomUUID().toString());
    }

    private static int distanceSquared(BlockPos position) {
        return position.getX() * position.getX() + position.getY() * position.getY() + position.getZ() * position.getZ();
    }

    private static JsonArray coordinates(int x, int y, int z) {
        JsonArray result = new JsonArray();
        result.add(x); result.add(y); result.add(z);
        return result;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void hash(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            // Length-prefix fields, so arbitrary registry/property text cannot create ambiguous concatenations.
            digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    private record ObservedBlock(BlockPos relative, String id, JsonObject properties, String stateKey,
                                 MachineSurveyModel.Hint hint, String entityType, JsonObject evidence) {}

    private static final class Capture {
        final int radius;
        final String worldScope;
        final List<ObservedBlock> blocks = new ArrayList<>();
        final Set<String> mods = new LinkedHashSet<>();
        String fingerprint;
