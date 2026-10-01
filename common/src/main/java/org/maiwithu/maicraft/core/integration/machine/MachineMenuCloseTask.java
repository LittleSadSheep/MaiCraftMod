// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.GuiPreparation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 按显式请求退出当前页面；先等在途菜单事务，再让原生返还鼠标物品及合成余料。
 */
public final class MachineMenuCloseTask extends AbstractCompanionTask<MachineMenuCloseTaskRecord> {
    private final GuiPreparation preparation = new GuiPreparation();
    private boolean verified;
    private String failureCode;

    public MachineMenuCloseTask(LocalPlayer player, MachineMenuCloseTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        // 关闭不扩大容器存取权限；只调用原生退出，确认界面和余料结清后完成同一次请求。
        try {
            if (!preparation.ready(context, false)) return TaskState.RUNNING;
            verified = true; return TaskState.SUCCESS;
        } catch (RuntimeException failure) {
            return failure("machine_menu_close_unconfirmed", failure.getMessage());
        }
    }

    private TaskState failure(String code, String message) {
        failureCode = code; fail(message, FailureType.UNKNOWN); return TaskState.FAILED;
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return !preparation.evidence().isEmpty() && !verified && !preparation.failed(); }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("menu_close_verified", verified);
        data.put("effects_started", !preparation.evidence().isEmpty());
        data.put("outcome_uncertain", preparation.uncertain());
        data.put("mechanical_retry_allowed", preparation.evidence().isEmpty());
        data.put("gui_preparation", preparation.evidence());
        if (failureCode != null) data.put("failure_code", failureCode);
        return data;
    }
    @Override protected String successMessage() { return "The menu is closed and ordinary world interaction is available."; }
}
