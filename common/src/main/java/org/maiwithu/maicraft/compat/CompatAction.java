// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 联动模组交出的动作，登记表给它包的一层：动作推进到一半时模组停用、或碰到模组接口对不上，
 * 按"不支持"如实收场并写明原因，不让它变成任务的内部错误。模组装错版本不是程序错误。
 */
class CompatAction implements Action {

    private final CompatModule module;
    private final Action action;

    CompatAction(CompatModule module, Action action) {
        this.module = Objects.requireNonNull(module, "module");
        this.action = Objects.requireNonNull(action, "action");
    }

    /** 包一层；投料动作带着投料实情（扔进去几件、没进去的在哪）的，包出来的这一层照样交得出实情。 */
    static CompatAction of(CompatModule module, Action action) {
        return action instanceof FeedReport report ? new Reporting(module, action, report)
                : new CompatAction(module, action);
    }

    /** 带投料实情的动作：实情照转；读实情只读动作自己记下的件数与说明，不碰模组。 */
    private static final class Reporting extends CompatAction implements FeedReport {

        private final FeedReport report;

        Reporting(CompatModule module, Action action, FeedReport report) {
            super(module, action);
            this.report = report;
        }

        @Override public int delivered() {
            return report.delivered();
        }

        @Override public List<String> notes() {
            return report.notes();
        }
    }

    @Override public ActionStatus tick(TickContext context) {
        if (!module.active()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明"), null));
        }
        try {
            return action.tick(context);
        } catch (ModApiMismatch broken) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, broken.getMessage(), null));
        }
    }

    // 暂停与收尾也可能碰到模组：对不上时不再往外抛，收尾照常结束。
    @Override public void pause() {
        try {
            action.pause();
        } catch (ModApiMismatch ignored) {
            // 已经停用，联动入口的日志里写过原因。
        }
    }

    @Override public void close() {
        try {
            action.close();
        } catch (ModApiMismatch ignored) {
            // 已经停用，联动入口的日志里写过原因。
        }
    }

    @Override public Interruptibility interruptibility() {
        return action.interruptibility();
    }

    @Override public String describe() {
        return action.describe();
    }
}
