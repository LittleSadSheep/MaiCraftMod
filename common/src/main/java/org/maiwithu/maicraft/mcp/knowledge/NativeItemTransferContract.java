// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 组件级库存规则来自原生源码，只说明供料接口，不替模型选择配方、坐标或整机布局。 */
public final class NativeItemTransferContract {
    private NativeItemTransferContract() {}
    public static JsonObject reference(String blockId) {
        // 接口能接收物品不等于相邻箱子会主动投料；运输器和被动库存必须分别说明。
        String source = switch (blockId) {
            case "create:deployer" -> """
                    {"role":"passive_filtered_inventory","source_version":"Create 6.0.11 NeoForge",
                     "source_methods":["DeployerBlockEntity.registerCapabilities","DeployerItemHandler.insertItem","DeployerItemHandler.extractItem"],
                     "item_input":{"faces":["up","down","north","south","east","west"],
                       "registration":"The NeoForge item capability ignores the queried face; the block entity and native handler must be initialized.",
                       "destination":"held item used by the deployer; overflow slots reject insertion",
                       "acceptance":"Deployer FilteringBehaviour must accept the item; an occupied hand only stacks identical item and components up to its native limit."},
                     "item_output":"Overflow is extractable. A held item matching an active nonempty filter is protected from extraction.",
                     "transport_requirement":"An external transporter must insert into the deployer's own block inventory. Adjacent buffers and create.filter alone do not transfer ingredients.",
                     "processing_distinction":"The held ingredient inventory is separate from the workpiece on the processing surface."}
                    """;
            case "minecraft:hopper" -> """
                    {"role":"active_item_transporter","source_version":"Minecraft 1.21.1; NeoForge 21.1 item hooks",
                     "source_methods":["HopperBlockEntity.tryMoveItems","HopperBlockEntity.ejectItems","HopperBlockEntity.suckInItems","VanillaInventoryCodeHooks.insertHook"],
                     "source_inventory":{"offset":[0,1,0],"queried_face":"down"},
                     "destination_inventory":{"offset":"one block in the hopper's facing direction","queried_face":"opposite of hopper facing"},
                     "activation":"Unpowered/enabled hopper, loaded inventories, accepted items, available capacity and expired cooldown.",
                     "cross_mod":"NeoForge's native hopper hook inserts into the destination IItemHandler. A different loader requires its installed transfer integration to be checked.",
                     "transfer_limit":"Successful movement normally starts an 8 tick cooldown; this is a component rule, not a measured production rate."}
                    """;
            default -> null;
        };
        if (source == null) return null;
        JsonObject result = JsonParser.parseString(source).getAsJsonObject();
        result.addProperty("block_id", blockId); result.addProperty("evidence", "audited_native_source_reference");
        result.addProperty("world_transfer_verified", false);
        result.addProperty("runtime_requirement", "Confirm the installed loader's native interface, current filter, capacity and actual transfer; this reference does not read or authorize any container.");
        return result;
    }
}
