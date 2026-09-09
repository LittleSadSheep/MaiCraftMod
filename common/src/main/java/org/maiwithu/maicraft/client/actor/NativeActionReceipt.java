// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 记住一次游戏操作的等待状态：何时提交、属于哪个玩家版本、等多久、观察到什么才算结束。 */
public final class NativeActionReceipt {
    public enum Kind { BREAK_BLOCK, USE_BLOCK, USE_ITEM, RELEASE_ITEM, SELECT_HOTBAR, CREATIVE_SET_SLOT, MOD_PROTOCOL, ATTACK_ENTITY, INTERACT_ENTITY }
    public enum Status { PENDING, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED, CANCELLED, DIVERGED, UNCERTAIN }

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long submittedTick;
    private final long deadlineTick;
    private final int stableTicksRequired;
    private final NativeConfirmation confirmation;
    private final BlockPos breakTarget;
    private final Direction breakFace;
    private Status status = Status.PENDING;
    private int stableTicks;
    private long lastStableTickRevision = Long.MIN_VALUE;
    private long lastNativeTick;
    private String detail = "awaiting authoritative client facts";

    NativeActionReceipt(
            Kind kind,
            LocalPlayerContext context,
            int timeoutTicks,
            int stableTicksRequired,
            NativeConfirmation confirmation,
            BlockPos breakTarget,
            Direction breakFace) {
        // 期限按客户端的动作刻计算；保存身体和控制版本，不能把旧玩家的点击结果用到新玩家身上。
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        if (stableTicksRequired < 1) throw new IllegalArgumentException("stableTicksRequired must be positive");
        this.kind = kind;
        this.bodyEpoch = context.bodyEpoch();
        this.controlRevision = context.controlRevision();
        this.submittedTick = context.tickRevision();
        this.deadlineTick = Math.addExact(submittedTick, timeoutTicks);
        this.stableTicksRequired = stableTicksRequired;
        this.confirmation = confirmation;
        this.breakTarget = breakTarget == null ? null : breakTarget.immutable();
        this.breakFace = breakFace;
        this.lastNativeTick = submittedTick;
    }

    public UUID id() { return id; }
    public Kind kind() { return kind; }
    public Status status() { return status; }
    public long bodyEpoch() { return bodyEpoch; }
    public long controlRevision() { return controlRevision; }
    public long submittedTick() { return submittedTick; }
    public long deadlineTick() { return deadlineTick; }
    public String detail() { return detail; }
    public boolean terminal() { return status != Status.PENDING; }

    NativeConfirmation confirmation() { return confirmation; }
    BlockPos breakTarget() { return breakTarget; }
    Direction breakFace() { return breakFace; }
    long lastNativeTick() { return lastNativeTick; }
    void nativeAdvanced(long tick) { lastNativeTick = tick; }
    void resetStable(long tickRevision) {
        // 条件没满足时重置稳定计数，同一刻重复查询不会重复计数或重置。
        if (lastStableTickRevision == tickRevision) return;
        lastStableTickRevision = tickRevision;
        stableTicks = 0;
    }
    boolean countStable(long tickRevision) {
        // 当前只数不同客户端刻的匹配次数，没有在这里等待服务器的方块预测确认序号。
        if (lastStableTickRevision != tickRevision) {
            lastStableTickRevision = tickRevision;
            stableTicks++;
        }
        return stableTicks >= stableTicksRequired;
    }
    void finish(Status status, String detail) {
        // 一旦结束就保留这个结论；后来的世界回滚不会通过这个方法重新打开已经结束的记录。
        if (terminal()) return;
        this.status = status;
        this.detail = detail;
    }
}
