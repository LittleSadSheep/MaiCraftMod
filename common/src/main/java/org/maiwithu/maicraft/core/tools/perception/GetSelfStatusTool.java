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
 * 读取自己的生命、饥饿、位置、装备和水下氧气，回复这一刻看到的状态。
 * 这是内部查询工具。结果中的结构列表固定为空，背包只有占用格数，不能据此推断完整的物品明细。
 */
public final class GetSelfStatusTool implements MaiCraftTool {

    @Override
    public String name() {
        return "get_self_status";
    }

    /** 标为常用的内部查询；这个标签不会自动把它变成公开 MCP 工具。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        // 查询说明后面附上自动自救名册；名册里有哪些文字与实际能否抢占身体是两回事。
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
        // 直接读取此刻的本地玩家状态；不替玩家做动作，也不等待下一次服务器更新。
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

        // 客户端没有完整的结构生成记录，所以空列表表示这里不知道，并不表示附近一定没有村庄等结构。
        root.add("structures", new JsonArray());
        root.addProperty("structures_note", "unknown from the local client; infer from visible loaded terrain");

        // 按装备栏位列物品；空栏位省略，数量为一时也省略 count。
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

        // 这里只统计已占用的格数，不列背包明细。getContainerSize 还包括盔甲和副手，
        // 所以下面的 backpack_slots 实际是整个 Inventory 的格数，并非仅背包的 36 格。
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
        // 剩余氧气和最大氧气用游戏刻表示，便于判断在水下还能撑多久。
        root.addProperty("air", self.getAirSupply() + "/" + self.getMaxAirSupply() + " ticks");
        root.addProperty("in_lava", self.isInLava());

        reply.accept(root.toString());
    }
}
