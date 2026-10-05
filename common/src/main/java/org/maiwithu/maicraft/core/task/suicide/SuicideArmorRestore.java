// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.task.inventory.EquipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/** 寻死前脱下的护甲在寻死结束后穿回：重生后的新身体或没死成的原身体都按原件逐件装回原部位，不换别的同类装备。 */
public final class SuicideArmorRestore implements Task, Reflex {
    public static final String ID = "suicide_armor_restore";
    // 重生后背包内容包可能晚到；从可以穿回起最多等这么多刻仍找不到原件，就如实报告没有穿回。
    private static final long INVENTORY_WAIT_TICKS = 200;
    private static final Map<EquipmentSlot, ItemStack> PENDING = new LinkedHashMap<>();
    private static UUID owner;
    private static long waitingSince = -1;
    private final List<String> restored = new ArrayList<>(), missed = new ArrayList<>();
    private EquipCompanionTask equip;
    private EquipmentSlot slot;
    private boolean episode;

    /** 寻死结束时登记本次确认脱下的护甲；同一部位以最新一次为准，旧登记中尚未穿回的其他部位继续保留。 */
    public static synchronized void remember(LocalPlayer player, Map<EquipmentSlot, ItemStack> pieces) {
        if (pieces.isEmpty()) return;
        if (!Objects.equals(player.getUUID(), owner)) PENDING.clear();
        owner = player.getUUID();
        pieces.forEach((slot, piece) -> PENDING.put(slot, piece.copy()));
        waitingSince = -1;
    }

    /** 断线或换世界时作废：上一个世界的护甲登记不能在另一份存档里自动穿装备。 */
    public static synchronized void reset() { PENDING.clear(); owner = null; waitingSince = -1; }

    /** 只读快照：还等着穿回的部位与原件，供诊断和回归核对，不能借此改登记。 */
    public static synchronized Map<EquipmentSlot, ItemStack> pending() { return Map.copyOf(PENDING); }

    @Override public boolean canRun(LocalPlayer player) {
        if (equip != null) return true;
        synchronized (SuicideArmorRestore.class) {
            if (PENDING.isEmpty() || !Objects.equals(player.getUUID(), owner)) return false;
            // 只在活着、死亡与重生交接已结清、原生操作空闲、没有打开其他界面时穿回；新的寻死步骤进行中则继续保持脱下。
            if (!player.isAlive() || player.isSpectator() || GameplayAttentionMonitor.blocksAutomation(player)
                    || suicideStep() || !ClientRuntime.actor().settledForRoutinePause()) return false;
            var context = ClientRuntime.requireContext(player);
            if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) return false;
            // 部位已经被别的装备占用时不再替换，视为不需要穿回；原件找到了就开始。
            PENDING.entrySet().removeIf(entry -> !player.getItemBySlot(entry.getKey()).isEmpty());
            if (PENDING.isEmpty()) return finishIdle(player);
            if (next(player) != null) return true;
            long now = player.level().getGameTime();
            if (waitingSince < 0) waitingSince = now;
            if (now - waitingSince < INVENTORY_WAIT_TICKS) return false;
            // 等满仍没在背包里看到原件（掉落、被拿走或模组改动），如实列为没有穿回，不去穿别的同类装备。
            PENDING.forEach((key, piece) -> missed.add(label(key, piece) + ": not found in the carried inventory"));
            PENDING.clear();
            return finishIdle(player);
        }
    }

    @Override public TaskState tick(LocalPlayer player) {
        // 逐件穿回：找原件 -> 交给原生装备任务拿到手上右键穿上 -> 核对部位 -> 下一件；全部结清后报告一次。
        if (equip == null) {
            Map.Entry<EquipmentSlot, ItemStack> entry;
            synchronized (SuicideArmorRestore.class) { entry = next(player); }
            if (entry == null) return TaskState.RUNNING;
            if (!episode) {
                episode = true;
                GameplayAttentionMonitor.reflexStarted(ID, "armor taken off before a deliberate suicide is in the inventory again",
                        "re-equip the same armor pieces", "no items consumed", "none expected");
            }
            slot = entry.getKey(); var piece = entry.getValue();
            equip = new EquipCompanionTask(player, new EquipTaskRecord("suicide-armor-restore",
                    player.level().getGameTime() + 200, piece.getItem(), slot, label(slot, piece), piece));
            equip.start(player);
            return TaskState.RUNNING;
        }
        TaskState state = equip.tick(player);
        if (!state.isTerminal()) return TaskState.RUNNING;
        var result = equip.result(state); equip = null;
        synchronized (SuicideArmorRestore.class) {
            var piece = PENDING.remove(slot);
            if (piece != null && result.success()) restored.add(label(slot, piece));
            else if (piece != null) missed.add(label(slot, piece) + ": " + result.message());
            if (PENDING.isEmpty()) finishIdle(player);
        }
        return TaskState.RUNNING;
    }

    private static Map.Entry<EquipmentSlot, ItemStack> next(LocalPlayer player) {
        // 只在主背包和快捷栏里找组件完全相同的原件，与装备任务的取物范围一致。
        for (var entry : PENDING.entrySet()) {
            for (int index = 0; index < 36; index++)
                if (ItemStack.isSameItemSameComponents(player.getInventory().getItem(index), entry.getValue())) return entry;
        }
        return null;
    }

    private static boolean suicideStep() {
        var current = CompanionTickDispatcher.current();
        return current instanceof IntentTaskRecord intent && intent.stepIndex() < intent.steps().size()
                && "maicraft:suicide".equals(intent.steps().get(intent.stepIndex()).ability());
    }

    private boolean finishIdle(LocalPlayer player) {
        // 这一轮登记全部结清：已开始的报告补上结束；从未开始就全部找不到时也发一对开始与结束，让模型知道护甲没有穿回。
        waitingSince = -1;
        if (restored.isEmpty() && missed.isEmpty()) return false;
        if (!episode) GameplayAttentionMonitor.reflexStarted(ID, "armor taken off before a deliberate suicide was registered for re-equipping",
                "re-equip the same armor pieces", "no items consumed", "none expected");
        var facts = new JsonObject();
        var done = new JsonArray(); restored.forEach(done::add); facts.add("armor_restored", done);
        var left = new JsonArray(); missed.forEach(left::add); facts.add("armor_not_restored", left);
        GameplayAttentionMonitor.reflexFinished(ID, missed.isEmpty() ? "armor_restored" : "armor_partly_restored",
                restored.size(), "no items consumed", "none", facts);
        restored.clear(); missed.clear(); episode = false;
        return false;
    }

    private static String label(EquipmentSlot slot, ItemStack piece) {
        return BuiltInRegistries.ITEM.getKey(piece.getItem()).getPath() + " (" + slot.getName() + ")";
    }

    @Override public void stop(LocalPlayer player, StopReason reason) {
        // 被紧急自救抢走身体时只暂停这一件；换身体或取消则收掉子任务，原件仍留在登记里，下次空闲再穿。
        if (equip == null) return;
        equip.stop(player, reason);
        if (reason != StopReason.PREEMPTED) { equip.result(TaskState.CANCELLED); equip = null; }
    }

    @Override public String name() { return "SuicideArmorRestore"; }
    @Override public String id() { return ID; }
    @Override public String describeCurrentAction() { return "正在穿回寻死前脱下的护甲"; }
    @Override public String describe() { return "寻死结束后（重生后或没死成时）把寻死前脱下的同一件护甲穿回原部位；部位已被占用或找不到原件时不替换并如实报告。"; }
}
