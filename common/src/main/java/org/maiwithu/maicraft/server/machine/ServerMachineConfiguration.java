// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.machine.ae2.Ae2MachineConfiguration;

/** Refreshes current configuration evidence without replaying a writer, consuming tools or posting use events. */
public final class ServerMachineConfiguration {
    private ServerMachineConfiguration() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        String action = ServerAccess.text(body, "action");
        BlockEntity entity = ServerAccess.check(player, ServerAccess.position(body.getAsJsonObject("position")), false);
        JsonObject result;
        try {
            if (entity == null) result = unknown("target_has_no_configuration");
            else if (action.startsWith("create.")) result = CreateMachineAdapter.configuration(player, entity, body);
            else if (action.startsWith("mekanism.")) result = MekanismMachineAdapter.configuration(player, entity, body);
            else if (action.startsWith("ae2.")) result = Ae2MachineConfiguration.inspect(player, entity, body);
            else result = unknown("unsupported_action");
        } catch (NativeApi.Unavailable | NativeApi.NativeFailure unsupported) { result = unknown("native_api_unavailable"); }
        catch (ServerOperationException unsupported) {
            if (unsupported.code().equals("invalid_argument") || unsupported.code().equals("permission_denied")) throw unsupported;
            result = unknown(unsupported.code());
        } catch (IllegalArgumentException incomplete) { result = unknown("configuration_identity_unrepresentable"); }
        boolean complete = !result.has("unknown_reason");
        boolean matched = complete && result.has("verified_configuration") && result.get("verified_configuration").getAsBoolean();
        result.addProperty("schema", "maicraft.machine_configuration.v1");
        result.addProperty("operation", "machine.configuration");
        result.addProperty("configuration_operation", "machine.configure");
        result.addProperty("action", action); result.add("position", body.get("position").deepCopy());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("status", !complete ? "unknown" : matched ? "matched" : "mismatch");
        result.addProperty("complete", complete); result.addProperty("verified_configuration", matched);
        result.addProperty("provenance", "server_native_current_configuration");
        return result;
    }

    public static JsonObject unknown(String reason) {
        JsonObject result = new JsonObject(); result.addProperty("unknown_reason", reason);
        result.addProperty("verified_configuration", false); return result;
    }
}
