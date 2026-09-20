// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.NativeItemPort;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 先绑定组件完全一致的一件样品，再只模拟原生取出／放入；空仓的后续供料不会被记作已有物资。 */
final class HopperItemRouteEvidence {
    private static final int MAX_SLOTS = 128;
    private HopperItemRouteEvidence() {}

    static JsonObject probe(HolderLookup.Provider registries, HopperConnectionInspection.Route route,
                            NativeItemPort endpoint, JsonObject request) {
        JsonObject result = result("unknown", "exact_item_sample_required");
        NativeItemPort hopper = new NativeItemPort.ContainerPort(route.hopper(), null);
        NativeItemPort source = route.pull() ? endpoint : hopper;
        NativeItemPort destination = route.pull() ? hopper : endpoint;
        if (source.slots() < 1 || source.slots() > MAX_SLOTS || destination.slots() < 1 || destination.slots() > MAX_SLOTS)
            return finish(result, "unknown", "hopper_resource_slots_outside_budget");
        // 双箱能力可能读取另一半尚未打开的战利品；这次路径只授权了一个格，不能借模拟操作偷偷开箱。
        if (compositeInventory(source) || compositeInventory(destination))
            return finish(result, "unknown", "composite_container_resource_scope_unproven");
        ItemStack sample = sample(registries, source, request, result);
        if (sample.isEmpty()) return result;
        JsonObject identity = ResourceIdentity.item(sample, registries);
        result.add("sample_identity", identity);
        result.addProperty("sample_resource_id", ResourceIdentity.key(identity));
        result.addProperty("sample_amount", 1);
        boolean extraction = false;
        for (int slot = 0; slot < source.slots(); slot++) {
            ItemStack current = source.stack(slot).copy();
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, sample)) {
                ItemStack extracted = source.extract(slot, 1, true);
                if (extracted.getCount() == 1 && ItemStack.isSameItemSameComponents(extracted, sample)) {
                    extraction = true;
                    result.addProperty("source_extraction_simulated", true);
                    result.addProperty("source_presence_verified", true);
                    break;
                }
            }
            // 已知无抽取过滤的原生库存允许先供料再抽出。自定义空 handler 没有这种证明，仍保持 unknown。
            if (futureExtraction(source, route, slot, sample)
                    && source.insert(slot, sample.copy(), true).isEmpty()) {
                extraction = true;
                result.addProperty("source_extraction_rule", "native_unfiltered_inventory_after_supply");
                result.addProperty("source_presence_required", true);
                break;
            }
        }
        if (!extraction) return finish(result, "unknown", "exact_sample_extraction_or_future_supply_unproven");
        for (int slot = 0; slot < destination.slots(); slot++) {
            // 只传副本和 simulate=true；接受一件物品只说明当前准入，不替代整批容量或实际输送回执。
            ItemStack remainder = destination.insert(slot, sample.copy(), true);
            if (remainder.isEmpty()) {
                result.addProperty("destination_insertion_simulated", true);
                return finish(result, "verified", "native_exact_item_admission_verified_flow_unobserved");
            }
        }
        return finish(result, "planned", "hopper_destination_rejects_exact_item_or_has_no_space");
    }

    private static ItemStack sample(HolderLookup.Provider registries, NativeItemPort source,
                                    JsonObject request, JsonObject result) {
        String selector = text(request, "resource"), expected = text(request, "resource_id");
        ResourceLocation id = selector == null ? null : ResourceLocation.tryParse(selector);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
        for (int slot = 0; slot < source.slots(); slot++) {
            ItemStack observed = source.stack(slot).copy();
            if (observed.isEmpty() || !id.equals(BuiltInRegistries.ITEM.getKey(observed.getItem()))) continue;
            if (expected == null || expected.equals(ResourceIdentity.key(ResourceIdentity.item(observed, registries)))) {
                result.addProperty("sample_origin", "observed_source_stack");
                return observed.copyWithCount(1);
            }
        }
        // opaque key 只做完整相等比较。注册表默认样品不能冒充带名字、损伤或其他组件的请求物品。
        ItemStack candidate = new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (!candidate.isEmpty() && expected != null
                && expected.equals(ResourceIdentity.key(ResourceIdentity.item(candidate, registries)))) {
            result.addProperty("sample_origin", "registry_default_identity_matched");
            return candidate;
        }
        finish(result, "unknown", "component_exact_sample_unavailable");
        return ItemStack.EMPTY;
    }

    static boolean futureExtraction(NativeItemPort source, HopperConnectionInspection.Route route,
                                            int slot, ItemStack sample) {
        // 推出动作直接读取本条路线的漏斗库存；别的容器或任意 handler 不能借 push 标记跳过抽取证明。
        if (!route.pull()) return source instanceof NativeItemPort.ContainerPort container && container.container() == route.hopper();
        if (source instanceof NativeItemPort.CapabilityPort capability) {
            Class<?> type = capability.handler().getClass();
            return type == NativeApi.type("net.neoforged.neoforge.items.wrapper.InvWrapper")
                    || type == NativeApi.type("net.neoforged.neoforge.items.VanillaHopperItemHandler");
        }
        if (source instanceof NativeItemPort.ContainerPort container) {
            return container.container().canTakeItem(route.hopper(), slot, sample)
                    && (!(container.container() instanceof WorldlyContainer sided)
                    || sided.canTakeItemThroughFace(slot, sample, container.side()));
        }
        return false;
    }

    private static boolean compositeInventory(NativeItemPort port) {
        if (port instanceof NativeItemPort.CapabilityPort capability
                && NativeApi.is(capability.handler(), "net.neoforged.neoforge.items.wrapper.InvWrapper"))
            return NativeApi.call(capability.handler(), "net.neoforged.neoforge.items.wrapper.InvWrapper", "getInv") instanceof CompoundContainer;
        return false;
    }

    static JsonObject summarize(List<ConnectionEvidence> edges) {
        JsonObject result = result("verified", "every_hopper_edge_admits_the_same_exact_item");
        String sample = null;
        boolean supplyRequired = false;
        for (ConnectionEvidence edge : edges) {
            JsonObject probe = edge.details().getAsJsonObject("resource_probe");
            if (probe == null || !"verified".equals(text(probe, "status")))
                return finish(result, "unknown", "one_or_more_hopper_edges_have_unproven_item_admission");
            String next = text(probe, "sample_resource_id");
            if (next == null || sample != null && !sample.equals(next))
                return finish(result, "unknown", "hopper_edges_do_not_share_one_exact_resource_identity");
            sample = next;
            supplyRequired |= probe.has("source_presence_required") && probe.get("source_presence_required").getAsBoolean();
        }
        if (sample == null) return finish(result, "unknown", "hopper_path_has_no_resource_evidence");
        result.addProperty("sample_resource_id", sample);
        result.addProperty("source_presence_required", supplyRequired);
        return result;
    }

    private static JsonObject result(String status, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("provenance", "native_hopper_direction_and_item_admission_simulation");
        result.addProperty("source_presence_verified", false);
        result.addProperty("source_extraction_simulated", false);
        result.addProperty("destination_insertion_simulated", false);
        result.addProperty("batch_capacity_verified", false);
        result.addProperty("flow_verified", false);
        result.addProperty("production_verified", false);
        return finish(result, status, reason);
    }
    private static JsonObject finish(JsonObject result, String status, String reason) {
        result.addProperty("status", status); result.addProperty("reason", reason); return result;
    }
    private static String text(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isString()
                ? value.get(key).getAsString() : null;
    }
}
