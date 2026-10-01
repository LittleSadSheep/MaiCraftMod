// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
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
    private static final Map<String, String> machineTasks = new LinkedHashMap<>();
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
            flush(); identity = next; ledger = new Ledger(); dirty = false; nextSave = 0; machineTasks.clear();
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
        // 自动接线即使中途失败，已落地的轮和轴也归入同一台机器，后续任务不能重新获得一份轮数额度。
        machineTasks.forEach((prefix, machine) -> {
            if (taskId.equals(prefix) || taskId.startsWith(prefix + "-")) ledger.associate(player.level().dimension().location().toString(), at, machine);
        });
        dirty = true;
    }
    public record Placement(BlockPos position, String block, String task, String machine) {}
    public static List<Placement> placements(LocalPlayer player) {
        if (!current(player)) return List.of();
        String dimension = player.level().dimension().location().toString();
        return ledger.cells.entrySet().stream().filter(row -> row.getKey().dimension().equals(dimension))
                .map(row -> new Placement(BlockPos.of(row.getKey().position()), row.getValue().block(), row.getValue().task(), row.getValue().machine())).toList();
    }
    public static void bindMachineTask(LocalPlayer player, String prefix, String machine) {
        if (!current(player)) return;
        machineTasks.put(prefix, machine);
        // 旧版本的确认记录可由原任务身份补齐机器归属；不根据材料名称或离机器较近就冒认施工效果。
        for (var row : placements(player)) if (row.task().equals(prefix) || row.task().startsWith(prefix + "-")) {
            ledger.associate(player.level().dimension().location().toString(), row.position(), machine); dirty = true;
        }
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
        private record Entry(String block, String task, String machine) {}
        private final Map<Cell, Entry> cells = new LinkedHashMap<>();
        void placed(String dimension, BlockPos at, String block, String task) {
            if (block.equals("minecraft:air") || task == null || task.isBlank()) throw new IllegalArgumentException("confirmed placement requires a block and source task");
            cells.put(new Cell(dimension, at.asLong()), new Entry(block, task, null));
        }
        void associate(String dimension, BlockPos at, String machine) {
            var key = new Cell(dimension, at.asLong()); var entry = cells.get(key);
            if (entry != null) cells.put(key, new Entry(entry.block(), entry.task(), machine));
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
                if (entry.machine() != null) row.addProperty("machine_scope", entry.machine());
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
                if (row.has("machine_scope")) result.associate(row.get("dimension").getAsString(),
                        BlockPos.of(row.get("position").getAsLong()), row.get("machine_scope").getAsString());
            }
            return result;
        }
    }
}
