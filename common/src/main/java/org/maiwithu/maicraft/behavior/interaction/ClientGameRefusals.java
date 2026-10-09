// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.game.serverlink.ReceivedConfirmations;

/**
 * 游戏拒绝读端：动作栏提示语与"服务端没有报告这次交互有结果"的信号。
 *
 * <p>动作栏文案是游戏事实，读不到如实给空。服务端只在交互真实生效（放置、破坏、使用）
 * 后推送确认；一段时间内确认流纹丝不动，就说明最近的交互没有被任何东西处理。
 * 这个信号是粗的：别的任务同时做成了事也会让确认流动起来，所以只回答"最近有没有服务端
 * 见到的成果"，不下"必定被拒绝了"的结论。
 */
public final class ClientGameRefusals {

    /** 多久没有新确认才算"交互没被处理"；半分钟是服务端推送与网络余量的宽松上限。 */
    static final long UNHANDLED_WINDOW_TICKS = 20L * 30;

    private final OverlayMessages overlayMessages;
    private final Supplier<List<ReceivedConfirmations.ServerConfirmation>> confirmations;
    private final LongSupplier clientTicks;
    private int seenCount;
    private long lastFlowTick;

    public ClientGameRefusals(OverlayMessages overlayMessages,
                              Supplier<List<ReceivedConfirmations.ServerConfirmation>> confirmations,
                              LongSupplier clientTicks) {
        this.overlayMessages = Objects.requireNonNull(overlayMessages);
        this.confirmations = Objects.requireNonNull(confirmations);
        this.clientTicks = Objects.requireNonNull(clientTicks);
        this.seenCount = confirmations.get().size();
        this.lastFlowTick = clientTicks.getAsLong();
    }

    /** 最近一条动作栏提示语；没有在显示时为空。 */
    public Optional<String> latestMessage() {
        String text = overlayMessages.latest();
        return text == null ? Optional.empty() : Optional.of(text);
    }

    /** 最近一段时间服务端没有推送过任何交互成果，最近的交互多半没被处理。 */
    public boolean interactionWasUnhandled() {
        List<ReceivedConfirmations.ServerConfirmation> recent = confirmations.get();
        long now = clientTicks.getAsLong();
        if (recent.size() != seenCount) {
            seenCount = recent.size();
            lastFlowTick = now;
            return false;
        }
        return now - lastFlowTick >= UNHANDLED_WINDOW_TICKS;
    }
}
