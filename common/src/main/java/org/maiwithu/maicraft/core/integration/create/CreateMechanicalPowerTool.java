// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.runSync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/**
 * 把语义层已经解析的位置和材料许可转换成机械接线任务；模型不需要自己列出每格路线，恢复时只携带内部续接凭据。
 */
public final class CreateMechanicalPowerTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();
    /** Initial liveness lease; verified survey, supply, travel and placement progress renew it. */
    private static final long INITIAL_LIVENESS_LEASE_TICKS = 2L * 60L * 20L;

    private record Args(
            String source_name, int source_x, int source_y, int source_z,
            String destination_name, int destination_x, int destination_y, int destination_z,
            String transmission, Boolean allow_free_receiver,
            String material_policy, List<String> allowed_sources, Boolean allow_harm,
            List<String> protected_labels, String continuation_token) {}

    @Override public String name() { return "connect_mechanical_power"; }

    @Override public String description() {
        return "Connect two already resolved semantic endpoint regions with verified first-person "
                + "Create placement. Coordinates are supplied only by MaiCraft's intent runtime; "
                + "the model never plans route cells. MaiCraft investigates the live route, "
                + "supplies its exact material ledger, investigates again, and starts placement "
                + "only after the fresh ledger is fully present.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("source_name", "Semantic source endpoint label.")
                .integer("source_x", "Internally resolved source X.")
                .integer("source_y", "Internally resolved source Y.")
                .integer("source_z", "Internally resolved source Z.")
                .string("destination_name", "Semantic destination endpoint label.")
                .integer("destination_x", "Internally resolved destination X.")
                .integer("destination_y", "Internally resolved destination Y.")
                .integer("destination_z", "Internally resolved destination Z.")
                .optionalEnum("transmission", "Automatic or encased chain drive.", "auto", "encased_chain_drive")
                .optionalBool("allow_free_receiver", "Allow nearest authoritative destination evidence to be a verified empty receiver.")
                .optionalEnum("material_policy", "Material source policy after route investigation.",
                        "ordinary", "storage_available", "inventory_only")
                .optionalStringArray("allowed_sources", "Permitted semantic acquisition sources; storage is tried before crafting.")
                .optionalBool("allow_harm", "Permit harmful acquisition only when explicitly true; never inferred.")
                .optionalStringArray("protected_labels", "Remembered resources/areas acquisition must preserve.")
                .optionalString("continuation_token",
                        "Opaque internal receipt supplied only by MaiCraft's semantic parent on an exact retry.")
                .build();
    }

    @Override public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed == null) throw new IllegalArgumentException("mechanical connection arguments are required");
        CreateMechanicalPower.Transmission transmission = switch (
                parsed.transmission() == null ? "auto" : parsed.transmission()) {
            case "auto" -> CreateMechanicalPower.Transmission.AUTO;
            case "encased_chain_drive" -> CreateMechanicalPower.Transmission.ENCASED_CHAIN_DRIVE;
            default -> throw new IllegalArgumentException("unsupported mechanical transmission");
        };
        var request = new CreateMechanicalPower.Request(
                new CreateMechanicalPower.Endpoint(parsed.source_name(),
                        new BlockPos(parsed.source_x(), parsed.source_y(), parsed.source_z())),
                new CreateMechanicalPower.Endpoint(parsed.destination_name(),
                        new BlockPos(parsed.destination_x(), parsed.destination_y(), parsed.destination_z())),
                transmission,
                true,
                Boolean.TRUE.equals(parsed.allow_free_receiver()));
        var policy = SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(
                parsed.material_policy());
        var sources = SemanticMaterialSupplyCoordinator.parseSources(
                parsed.allowed_sources());
        UUID continuation = null;
        if (parsed.continuation_token() != null && !parsed.continuation_token().isBlank()) {
            try {
                continuation = UUID.fromString(parsed.continuation_token());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid internal mechanical continuation receipt");
            }
        }
        long deadline = ctx(toolCallId, player).deadline(INITIAL_LIVENESS_LEASE_TICKS);
        var task = continuation == null
                ? CreateMechanicalPower.task(
                        toolCallId, deadline, request, policy, sources,
                        Boolean.TRUE.equals(parsed.allow_harm()), parsed.protected_labels())
                : CreateMechanicalPower.resumeTask(
                        toolCallId, deadline, request, policy, sources,
                        Boolean.TRUE.equals(parsed.allow_harm()), parsed.protected_labels(), continuation);
        runSync(player, task, reply);
    }
}
