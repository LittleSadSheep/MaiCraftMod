package org.maiwithu.maicraft.core.tools.perception;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A local-client query that replies from currently synchronized player and loaded-world facts.
 * No arguments means an empty schema.
 */
public final class GetSelfStatusTool implements MaiCraftTool {

    @Override
    public String name() {
        return "get_self_status";
    }

    /** 常驻:每轮都可能要看自己的状态。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        // The reflex overview rides THIS description (constitution §6): maicraft-api
        // exposes no system-prompt injection channel to core, but every request
        // re-reads tool descriptions, so the model sees the current roster each
        // turn. Dynamic on purpose — switched-off reflexes drop out of the text.
        String base = "Read your body's condition in one call: name, game mode, HP / max HP, "
                + "hunger / saturation, position, dimension, biome, the structures you are "
                + "standing in, what you are wearing, and movement state. ALWAYS call this before "
                + "combat or planning decisions. It does NOT list your backpack — what you carry "
                + "is already in front of you every turn; use inspect_gui when exact slots matter. "
                + "No arguments.";
        String overview = org.maiwithu.maicraft.task.reflex.ReflexRegistry.overview();
        return overview.isEmpty() ? base : base + "\n\n" + overview;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        JsonObject root = new JsonObject();
        root.addProperty("entity_id", self.getId());
        root.addProperty("name", self.getName().getString());
        root.addProperty("game_mode", ClientRuntime.requireContext(self).gameMode().getPlayerMode().getName());
        root.addProperty("hp", self.getHealth());
        root.addProperty("max_hp", self.getMaxHealth());
        root.addProperty("hunger", self.getFoodData().getFoodLevel());
        root.addProperty("saturation", self.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", self.getX());
        pos.addProperty("y", self.getY());
        pos.addProperty("z", self.getZ());
        root.add("position", pos);

        root.addProperty("dimension", self.level().dimension().location().toString());
        root.addProperty("biome", self.level().getBiome(self.blockPosition())
                .unwrapKey().map(k -> k.location().toString()).orElse("unknown"));

        // Structure starts are server-only facts. Never substitute a hidden locate query.
        root.add("structures", new JsonArray());
        root.addProperty("structures_note", "unknown from the local client; infer from visible loaded terrain");

        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = self.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            if (s.getCount() > 1) o.addProperty("count", s.getCount());
            equipment.add(slot.getName(), o);
        }
        root.add("equipment", equipment);

        // 背包不在这里。它是「状态」不是「事件」——工具结果会沉进对话历史,而历史里的
        // 状态永远不会过期:十轮之后她读到那份快照,上面写的还是十轮前的东西,而且和这一轮
        // 挂在请求里的实时背包对不上。全量背包只有一个来源(runtime_state 的 <inventory>),
        // 那一份永远是现在。要精确到槽位就调 inspect_gui。
        var inv = self.getInventory();
        JsonObject slots = new JsonObject();
        int used = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) used++;
        }
        slots.addProperty("used", used);
        slots.addProperty("total", inv.getContainerSize());
        root.add("backpack_slots", slots);

        root.add("target", JsonNull.INSTANCE);
        root.addProperty("on_ground", self.onGround());
        root.addProperty("in_water", self.isInWater());
        // Remaining breath — the one stat whose absence let a body drown while its
        // mind calmly planned an 870-block trip (frozen-ocean death, 2026-07-15).
        root.addProperty("air", self.getAirSupply() + "/" + self.getMaxAirSupply() + " ticks");
        root.addProperty("in_lava", self.isInLava());

        reply.accept(root.toString());
    }
}
