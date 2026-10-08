// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 一次已提交游戏操作的等待记录：何时提交、出自哪位角色、等多久、观察到什么才算结束。 */
public final class PendingInteraction {
    public enum Kind { BREAK_BLOCK, USE_BLOCK, USE_ITEM, RELEASE_ITEM, SELECT_HOTBAR, DROP_SELECTED, CREATIVE_SET_SLOT, MOD_PROTOCOL, ATTACK_ENTITY, INTERACT_ENTITY }
    public enum Status { PENDING, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED, CANCELLED, DIVERGED, UNCERTAIN }

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    /** 提交时的角色对象；换了玩家对象（重生、换世界）后旧提交的结果不再可信。 */
    private final LocalPlayer submittedPlayer;
    private final long submittedTick;
    private final long deadlineTick;
    private final int stableTicksRequired;
    private final InteractionConfirmation confirmation;
    private final BlockPos breakTarget;
    private final Direction breakFace;
    private Status status = Status.PENDING;
    private int stableTicks;
    private long lastStableTickRevision = Long.MIN_VALUE;
    private long lastNativeTick;
    private String detail = "awaiting authoritative client facts";
    /** 右键方块提交现场的插桩事实（点击面、客户端预测结果、预测包是否发出）；只有 useBlock 提交时写入一次。 */
    private String useOnTrace = "";

    PendingInteraction(
            Kind kind,
            PlayerContext context,
            int timeoutTicks,
            int stableTicksRequired,
            InteractionConfirmation confirmation,
            BlockPos breakTarget,
            Direction breakFace) {
        // 期限按客户端的动作刻计算；保存提交时的角色对象，不能把旧玩家的点击结果用到新玩家身上。
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        if (stableTicksRequired < 1) throw new IllegalArgumentException("stableTicksRequired must be positive");
        this.kind = kind;
        this.submittedPlayer = context.localPlayer();
        this.submittedTick = context.clientTick();
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
    public long submittedTick() { return submittedTick; }
    public long deadlineTick() { return deadlineTick; }
    public String detail() { return detail; }
    /** 本次右键方块提交现场的插桩事实；非 useBlock 提交为空串。 */
    public String useOnTrace() { return useOnTrace; }
    /** 提交现场的插桩事实随确认记录走，避免全局最新值被后续提交覆盖后张冠李戴。 */
    void attachUseOnTrace(String trace) { if (useOnTrace.isEmpty()) useOnTrace = trace == null ? "" : trace; }
    public boolean terminal() { return status != Status.PENDING; }

    InteractionConfirmation confirmation() { return confirmation; }
    BlockPos breakTarget() { return breakTarget; }
    Direction breakFace() { return breakFace; }
    long lastNativeTick() { return lastNativeTick; }
    boolean fromSamePlayer(PlayerContext context) { return submittedPlayer == context.localPlayer(); }
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
