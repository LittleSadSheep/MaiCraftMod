// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 保留EMI资源的展示数量、展示概率、组件及返还物；扩展媒体未知时不因它恰好返回某个Item键而冒认为物品。 */
final class EmiStackReader {
    private static final int MAX_STACKS = 512, MAX_COMPONENT_BYTES = 16_384, MAX_REMAINDER_DEPTH = 3;
    private final EmiPublicApi api;
    private final HolderLookup.Provider registries;
    private int visited;
    private boolean complete = true;
    EmiStackReader(EmiPublicApi api, HolderLookup.Provider registries) { this.api = api; this.registries = registries; }
    boolean complete() { return complete; }
    boolean capacityAvailable() { return visited < MAX_STACKS; }

    JsonObject read(Object value) { return read(value, 0, Collections.newSetFromMap(new IdentityHashMap<>())); }
    private JsonObject read(Object value, int depth, Set<Object> path) {
        if (++visited > MAX_STACKS || value == null) return unavailable("stack_description_budget_or_missing_stack");
        if (!path.add(value)) return unavailable("cyclic_remainder");
        try {
            JsonObject out = new JsonObject(); String medium = medium(value);
            out.addProperty("medium", medium); out.addProperty("implementation", value.getClass().getName());
            Object id = api.call(api.stack(), value, "getId");
            if (id == null) out.add("id", JsonNull.INSTANCE); else out.addProperty("id", id.toString());
            out.addProperty("amount", ((Number) api.call(api.stack(), value, "getAmount")).longValue());
            float chance = ((Number) api.call(api.stack(), value, "getChance")).floatValue();
            if (!Float.isFinite(chance)) return unavailable("non_finite_stack_chance");
            // 展示堆的默认概率不证明模组原生保底；JEMI未传递的概率不会在知识层被升级为100%产量。
            out.addProperty("display_chance", chance); out.addProperty("empty", Boolean.TRUE.equals(api.call(api.stack(), value, "isEmpty")));
            out.addProperty("unit", medium.equals("items") ? "items" : medium.equals("fluids") ? "emi_native_fluid_units" : "unknown");
            if (medium.equals("unknown")) { out.addProperty("medium_unresolved", true); complete = false; }
            components(out, value, medium);
            Object remainder = api.call(api.stack(), value, "getRemainder");
            if (remainder != null && !Boolean.TRUE.equals(api.call(api.stack(), remainder, "isEmpty"))) {
                if (depth >= MAX_REMAINDER_DEPTH || path.contains(remainder)) {
                    out.addProperty("remainder_truncated", true); complete = false;
                } else out.add("remainder", read(remainder, depth + 1, path));
            }
            return out;
        } finally { path.remove(value); }
    }

    private String medium(Object value) {
        // 仅认EMI原有具体表示；第三方继承或新媒体有自己的语义，交给后续原生机制判断，不从getKey猜测。
        if (value.getClass() == api.itemStack()) return "items";
        if (value.getClass() == api.fluidStack()) return "fluids";
        if (value.getClass() == api.emptyStack()) return "empty";
        return "unknown";
    }

    private void components(JsonObject out, Object value, String medium) {
        try {
            Object data = api.call(api.stack(), value, "getComponentChanges");
            if (!(data instanceof DataComponentPatch patch) || patch.entrySet().stream().anyMatch(e -> e.getKey().isTransient()))
                throw new IllegalStateException("unsupported or transient EMI components");
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            JsonElement changes = DataComponentPatch.CODEC.encodeStart(ops, patch).getOrThrow();
            JsonElement effective = null;
            if (medium.equals("items")) {
                // EMI金额是long，getItemStack会转int；只构造一件只读样本展开默认组件，金额仍保留原long而不截断。
                Object key = api.call(api.stack(), value, "getKey");
                if (!(key instanceof Item item)) throw new IllegalStateException("EMI item stack has no native item key");
                ItemStack sample = new ItemStack(item); sample.applyComponents(patch);
                if (sample.getComponents().stream().anyMatch(component -> component.type().isTransient()))
                    throw new IllegalStateException("transient effective item components");
                effective = DataComponentMap.CODEC.encodeStart(ops, sample.getComponents()).getOrThrow();
            }
            JsonObject encoded = new JsonObject(); encoded.add("component_changes", changes);
            if (effective != null) encoded.add("components", effective);
            if (encoded.toString().getBytes(StandardCharsets.UTF_8).length > MAX_COMPONENT_BYTES)
                throw new IllegalStateException("EMI components exceed description budget");
            // 返回时保留整份可编码组件及显式移除项；过大或无法编码就明确缺失，不能截半份NBT后宣称身份相同。
            encoded.entrySet().forEach(entry -> out.add(entry.getKey(), entry.getValue()));
            out.addProperty("components_complete", true);
        } catch (RuntimeException | LinkageError unreadable) {
            out.addProperty("components_complete", false); out.addProperty("component_issue", "components_unreadable_or_exceed_budget"); complete = false;
        }
    }

    private JsonObject unavailable(String issue) {
        complete = false; JsonObject out = new JsonObject(); out.addProperty("medium", "unknown");
        out.addProperty("details_complete", false); out.addProperty("issue", issue); return out;
    }
}
