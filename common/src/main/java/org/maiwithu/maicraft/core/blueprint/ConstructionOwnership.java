// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 原生放置确认后记录施工归属；未完成的轴线和临时支撑也随世界、玩家保存，不能等整机成功才认领。 */
public final class ConstructionOwnership {
    private static final IntentStateStore STORE = new IntentStateStore();
    private static volatile StateIdentity identity;
    private static Object world;
    private static UUID playerId;
    private static Ledger ledger = new Ledger();
    private static volatile boolean dirty;
    private static long nextSave;
    private ConstructionOwnership() {}

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            flush(); world = null; playerId = null; return;
        }
        if (!bind(minecraft)) return;
        if (dirty && minecraft.level.getGameTime() >= nextSave) {
            flush(); nextSave = minecraft.level.getGameTime() + 20;
        }
    }
    private static boolean bind(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.player.getUUID() == null) return false;
        if (world == minecraft.level && minecraft.player.getUUID().equals(playerId)) return identity != null;
        var resolved = StateIdentity.resolve(minecraft); if (resolved.isEmpty()) return false;
        var base = resolved.get(); var owner = minecraft.player.getUUID();
        var next = new StateIdentity(base.key(), base.directory().resolve("owned-construction").resolve(owner.toString()));
        if (!next.equals(identity)) {
            flush(); identity = next; ledger = new Ledger(); dirty = false; nextSave = 0;
            var loaded = STORE.load(next);
            if (loaded.status() == IntentStateStore.Status.LOADED) {
                try { ledger = Ledger.decode(loaded.root()); }
                catch (RuntimeException invalid) { Constants.LOG.warn("MaiCraft construction ownership could not be restored ({})", invalid.getClass().getSimpleName()); }
            }
        }
        world = minecraft.level; playerId = owner; return true;
    }
    private static boolean current(LocalPlayer player) {
        var minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.player == player && minecraft.level == player.level() && bind(minecraft);
    }
    public static void placed(LocalPlayer player, BlockPos at, String taskId) {
        if (!current(player) || !player.level().isLoaded(at)) return;
        var state = player.level().getBlockState(at);
        if (state.isAir()) return;
        ledger.placed(player.level().dimension().location().toString(), at, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), taskId);
        dirty = true;
    }
    public static void removed(LocalPlayer player, BlockPos at) {
        if (current(player)) dirty |= ledger.remove(player.level().dimension().location().toString(), at);
    }
    public static boolean owns(LocalPlayer player, BlockPos at, BlockState actual) {
        return !actual.isAir() && current(player) && ledger.owns(player.level().dimension().location().toString(), at,
                BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString());
    }
    private static void flush() {
        if (!dirty || identity == null) return;
        try {
            // 主线程只捕获当前记录，复用现有合并写盘器；同一秒的多格施工不会同步刷盘卡住身体动作。
            var captured = identity; dirty = false;
            STORE.saveAsync(captured, ledger.encode(captured.key())).whenComplete((ignored, failure) -> {
                if (failure != null && captured.equals(identity)) dirty = true;
            });
        } catch (Exception failure) { dirty = true; Constants.LOG.warn("MaiCraft construction ownership save deferred ({})", failure.getClass().getSimpleName()); }
    }

    /** 只保存本方的确认事件，读取地标、计划目标或看见相同方块都不会调用 placed。 */
    static final class Ledger {
        private record Cell(String dimension, long position) {}
        private record Entry(String block, String task) {}
        private final Map<Cell, Entry> cells = new LinkedHashMap<>();
        void placed(String dimension, BlockPos at, String block, String task) {
            if (block.equals("minecraft:air") || task == null || task.isBlank()) throw new IllegalArgumentException("confirmed placement requires a block and source task");
            cells.put(new Cell(dimension, at.asLong()), new Entry(block, task));
        }
        boolean remove(String dimension, BlockPos at) { return cells.remove(new Cell(dimension, at.asLong())) != null; }
        boolean owns(String dimension, BlockPos at, String block) {
            var entry = cells.get(new Cell(dimension, at.asLong())); return entry != null && entry.block().equals(block);
        }
        JsonObject encode(String worldKey) {
            var root = new JsonObject(); root.addProperty("version", IntentStateStore.VERSION); root.addProperty("identity_key", worldKey);
            root.addProperty("evidence", "native_confirmed_placement"); var rows = new JsonArray(); root.add("owned_cells", rows);
            cells.forEach((cell, entry) -> {
                var row = new JsonObject(); row.addProperty("dimension", cell.dimension()); row.addProperty("position", cell.position());
                row.addProperty("block_id", entry.block()); row.addProperty("source_task", entry.task()); rows.add(row);
            });
            return root;
        }
        static Ledger decode(JsonObject root) {
            if (!root.has("evidence") || !root.get("evidence").getAsString().equals("native_confirmed_placement"))
                throw new IllegalArgumentException("ownership evidence unavailable");
            var result = new Ledger();
            for (var element : root.getAsJsonArray("owned_cells")) {
                var row = element.getAsJsonObject();
                result.placed(row.get("dimension").getAsString(), BlockPos.of(row.get("position").getAsLong()),
                        row.get("block_id").getAsString(), row.get("source_task").getAsString());
            }
            return result;
        }
    }
}
