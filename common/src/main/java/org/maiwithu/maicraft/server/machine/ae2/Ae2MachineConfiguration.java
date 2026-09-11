// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Called only after the common server registry has checked the real player's target interaction. */
public final class Ae2MachineConfiguration {
    private Ae2MachineConfiguration() {}

    public static JsonObject configure(ServerPlayer player, BlockEntity entity, JsonObject body) {
        Object host = Ae2ConfigurationAccess.host(entity, body);
        return switch (ServerAccess.text(body, "action")) {
            case "ae2.pattern_install" -> Ae2PatternInstallation.install(player, host, body);
            case "ae2.bus_filter" -> Ae2BusConfiguration.configure(player, host, body);
            default -> throw ServerAccess.denied("unsupported", "Unknown AE2 configuration action");
        };
    }

    public static JsonObject inspect(ServerPlayer player, BlockEntity entity, JsonObject body) {
        Object host = Ae2ConfigurationAccess.host(entity, body);
        return switch (ServerAccess.text(body, "action")) {
            case "ae2.pattern_install" -> Ae2ConfigurationReading.pattern(player, host, body);
            case "ae2.bus_filter" -> Ae2ConfigurationReading.bus(player, host, body);
            default -> org.maiwithu.maicraft.server.machine.ServerMachineConfiguration.unknown("unsupported_action");
        };
    }
}
