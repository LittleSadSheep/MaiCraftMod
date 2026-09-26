// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.integration.create.CreateProcessingCapabilities;
import org.maiwithu.maicraft.core.integration.machine.MachineAssemblyDocumentTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/** 模型可发现同一份组合契约，未知接口保持未知；示例产物不决定 schema 或安装原语。 */
public final class MachineAssemblyResourcesTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        KnowledgeLibrary library = KnowledgeLibrary.offline();
        JsonObject request = new JsonObject(); request.addProperty("action", "search"); request.addProperty("query", "机械手");
        check(library.request(request).toString().contains(MachineAssemblyResources.URI), "machine processing contract is discoverable without Ponder or a running world");
        var document = library.read(MachineAssemblyResources.URI);
        var body = JsonParser.parseString(document.text()).getAsJsonObject();
        check(body.get("protocol").getAsString().equals("explicit_machine_assembly_v1"), "the read response identifies the composition protocol");
        check(body.getAsJsonObject("blueprint_schema").getAsJsonObject("properties").has("assembly"), "author receives an explicit assembly schema");
        check(body.get("recipe_planning").getAsString().contains("backing_recipe.definition"), "recipe guidance names the actual native definition field");
        MachineBlueprintDocument.validateWire(MachineAssemblyDocumentTest.sample());
        var unavailable = CreateProcessingCapabilities.descriptor(Blocks.STONE.defaultBlockState());
        check(unavailable.get("external_workpiece_processor").isJsonNull() && unavailable.has("unknown"), "missing adapter evidence is not reported as a complete capability inventory");
        check(!body.toString().contains("precision_mechanism_station"), "resource contains no product-specific factory template");
        // 手动递交与原生物流各有真实动作，知识层不能把箱体和运输器说成机械手供料的必需品。
        var deployer = NativeItemTransferContract.reference("create:deployer");
        check(deployer.getAsJsonObject("manual_input").get("method").getAsString().equals("DeployerBlock.useItemOn"), "manual hand swap has a native source");
        check(deployer.getAsJsonObject("manual_input").get("effect").getAsString().contains("entire held stack"), "manual interaction exchanges, rather than silently merges, stacks");
        check(deployer.has("automated_input") && !deployer.has("transport_requirement") && !deployer.get("world_transfer_verified").getAsBoolean(), "transport is a supply choice and remains unverified");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
