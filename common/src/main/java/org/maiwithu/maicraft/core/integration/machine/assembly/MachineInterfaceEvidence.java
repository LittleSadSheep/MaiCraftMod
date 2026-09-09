// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * 通过 NeoForge 接口读取某一面的库存、储液槽或能量口信息，不实际存取。Fabric 没有这套接口时会报告无法读取。
 * 看到接口只代表能查到它，不代表两端资源相容或已经传输成功；物品和流体这里只报告槽数。
 */
public final class MachineInterfaceEvidence {
    private MachineInterfaceEvidence() {}

    public static JsonObject inspect(Level level, BlockPos position, Direction face, String medium) {
        JsonObject result = new JsonObject();
        result.addProperty("face", face.getSerializedName());
        result.addProperty("medium", medium);
        result.addProperty("resource_compatibility_verified", false);
        if (!level.isLoaded(position)) { result.addProperty("status", "unloaded"); return result; }
        String nested = switch (medium) {
            case "items", "item" -> "ItemHandler";
            case "fluids", "fluid" -> "FluidHandler";
            case "energy" -> "EnergyStorage";
            default -> null;
        };
        if (nested == null) { result.addProperty("status", "unsupported_medium"); return result; }
        try {
            Class<?> capability = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
            Object type = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$" + nested).getField("BLOCK").get(null);
            Method getter = level.getClass().getMethod("getCapability", capability, BlockPos.class, Object.class);
            Object port = getter.invoke(level, type, position, face);
            if (port == null) {
                // Many providers deliberately return null client-side; absence here cannot prove no server interface.
                result.addProperty("status", "no_client_capability_observed");
                return result;
            }
            result.addProperty("status", "observed");
            result.addProperty("capability_present", true);
            if (nested.equals("EnergyStorage")) {
                Class<?> api = Class.forName("net.neoforged.neoforge.energy.IEnergyStorage");
                result.addProperty("input_enabled", (Boolean) api.getMethod("canReceive").invoke(port));
                result.addProperty("output_enabled", (Boolean) api.getMethod("canExtract").invoke(port));
            } else {
                Class<?> api = Class.forName(nested.equals("ItemHandler")
                        ? "net.neoforged.neoforge.items.IItemHandler" : "net.neoforged.neoforge.fluids.capability.IFluidHandler");
                int count = ((Number) api.getMethod(nested.equals("ItemHandler") ? "getSlots" : "getTanks").invoke(port)).intValue();
                result.addProperty(nested.equals("ItemHandler") ? "slot_count" : "tank_count", count);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            result.addProperty("status", "capability_api_unavailable");
        }
        return result;
    }
}
