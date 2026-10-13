// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连锁挖一格：按住连锁键挖触发格，模组把整批一起挖掉；不连锁时就是普通地挖这一格。
 * 第一下之前问连锁键准备好没有 → 挖触发格（连锁时一直按着）→ 那一下确认后再等副格的方块更新到齐（最多一秒）
 * → 松键、等模组按松开处理 → 做完。挖到一半键丢了（形状被换、工具换了）：停手、松键，改成只挖这一格。
 * 被打断时同样先松键，回来重新开一次连锁。哪些副格真挖掉了由调用方逐格复查。
 */
final class ChainedDig implements Action {

    private static final Logger LOG = LoggerFactory.getLogger(ChainedDig.class);

    /** 挥不下去每隔这么多刻记一行。 */
    private static final int STALL_LOG_TICKS = 100;
    /** 挥不下去已经多少刻。 */
    private int ticksStalled;
    /** 副格的方块更新最多等几刻：模组在同一刻挖完整批，更新随后几个包陆续到。 */
    static final int SETTLE_TICKS = 20;
    /** 要求的那一面这么多刻都打不中就不再挑面：站位偏了照样挖下去，形状不对由那一批的检查兜住。 */
    static final int FACE_GIVE_UP_TICKS = 40;

    /** 读现场：这一格还在不在。 */
    interface Cells {
        boolean gone(TickContext context, BlockPos cell);
    }

    private enum Stage { DIG, SETTLE, RELEASE, DONE }

    private final BlockPos trigger;
    private final BreakAccelerator.ChainShape shape;
    private final Predicate<BlockPos> mayBreak;
    private final List<BreakAccelerator> accelerators;
    private final BlockBreaking digging;
    private final Cells cells;
    private Stage stage = Stage.DIG;
    /** 这一轮开出来的连锁键；不连锁（没装、答了只挖这一格）时为空。 */
    private ChainKeyHold hold;
    private boolean holdOpened;
    /** 准备好时那一批会一起挖的格；只挖这一格时为空。 */
    private List<BlockPos> batch = List.of();
    /** 挖的中途键丢了：先松开，松好再接着挖这一格。 */
    private boolean droppingChain;
    private PlayerContext current;
    private long settleUntil;
    private int ticksWithoutSwing;
    private boolean faceRequired;
    private ActionStatus.Failed failure;
    /** 上一次记下的"在等什么"：换了才再记一行。 */
    private String lastWait = "";
    /** 这次为什么没连锁：给描述用。 */
    private String singleReason = "";

    ChainedDig(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak,
            List<BreakAccelerator> accelerators, BlockBreaking digging, Cells cells) {
        this.trigger = trigger.immutable();
        this.shape = Objects.requireNonNull(shape, "shape");
        this.mayBreak = Objects.requireNonNull(mayBreak, "mayBreak");
        this.accelerators = List.copyOf(accelerators);
        this.digging = Objects.requireNonNull(digging, "digging");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.faceRequired = face != null;
        digging.aimAt(this.trigger);
        digging.fromFace(face);
        digging.beforeFirstSwing(this::gate);
    }

    @Override public ActionStatus tick(TickContext context) {
        current = context.player();
        return switch (stage) {
            case DONE -> failure != null ? failure : ActionStatus.done();
            case RELEASE -> released();
            case SETTLE -> settle(context);
            case DIG -> dig(context);
        };
    }

    // 挖触发格：先开连锁键；挥下去以后每刻续按，键丢了就停手松键改成只挖这一格。
    private ActionStatus dig(TickContext context) {
        if (!holdOpened) open();
        if (!batch.isEmpty() && digging.swingPending() && !hold.keep(current)) {
            digging.pause();
            batch = List.of();
            droppingChain = true;
            singleReason = "挖到一半连锁键松掉了";
        }
        if (droppingChain) {
            if (!hold.release(current)) return ActionStatus.running();
            hold = null;
            droppingChain = false;
        }
        // 触发格没了、也没有等确认的那一下：要么刚挖掉，要么别人挖了，都不再对着空气挖。
        if (cells.gone(context, trigger) && !digging.swingPending()) return dug(context);
        ActionStatus status = digging.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            failure = failed;
            stage = Stage.RELEASE;
            return ActionStatus.running();
        }
        // 挖掘器本刻确认挖开了（只挖目标、不拆遮挡，进展就是触发格那一下）。
        if (status instanceof ActionStatus.Done
                || status instanceof ActionStatus.Running running && running.progressed()) {
            return dug(context);
        }
        // 挥不下去好几秒：记一行挖掘器此刻在做什么，实机对得上卡在哪。
        if (!digging.swingPending() && ++ticksStalled % STALL_LOG_TICKS == 0) {
            LOG.info("[连锁挖] {}：{} 刻还没挥下去，挖掘器在{}", trigger.toShortString(), ticksStalled, digging.describe());
        }
        // 要求的面一直打不中（站位斜了、上一行还没挖开）：不再挑面，哪面看得见挖哪面。
        if (faceRequired && !digging.swingPending() && ++ticksWithoutSwing > FACE_GIVE_UP_TICKS) {
            faceRequired = false;
            digging.fromFace(null);
        }
        return ActionStatus.running();
    }

    // 触发格挖掉了：连锁的要再等副格更新到齐才松键；只挖这一格的直接收尾。
    private ActionStatus dug(TickContext context) {
        if (!batch.isEmpty()) {
            stage = Stage.SETTLE;
            settleUntil = context.gameTick() + SETTLE_TICKS;
        } else {
            stage = Stage.RELEASE;
        }
        return ActionStatus.progressed();
    }

    // 等副格：整批都没了或者等满一秒就松键；模组按耐久、饥饿半路停下的那几格留给调用方复查。
    private ActionStatus settle(TickContext context) {
        boolean allGone = batch.stream().allMatch(cell -> cells.gone(context, cell));
        if (!allGone && context.gameTick() < settleUntil) return ActionStatus.running();
        stage = Stage.RELEASE;
        return released();
    }

    // 松键：模组按松开处理了才算做完，不然下一次挖还带着连锁。
    private ActionStatus released() {
        if (hold != null && !hold.release(current)) return ActionStatus.running();
        hold = null;
        stage = Stage.DONE;
        return failure != null ? failure : ActionStatus.done();
    }

    // 开这一轮的连锁键：按顺序问联动的挖掘加速，第一个开得出的就用它；都开不出就普通地挖。
    private void open() {
        holdOpened = true;
        for (BreakAccelerator accelerator : accelerators) {
            Optional<ChainKeyHold> opened = accelerator.hold(shape, mayBreak);
            if (opened.isPresent()) {
                hold = opened.get();
                return;
            }
        }
        singleReason = "没有连锁挖";
    }

    // 第一下挥下去之前：连锁键说再等就先不挥；说可以连锁就记下这一批；说只挖这一格就照常挥。
    private boolean gate(BlockHitResult hit) {
        if (hold == null || droppingChain) return true;
        ChainKeyHold.Readiness readiness = hold.ready(current, hit);
        if (readiness instanceof ChainKeyHold.Readiness.Wait wait) {
            // 在等什么记一行（换了才记）：实机连锁卡住一分钟不挥第一下时，看得出卡在哪一步。
            if (!wait.why().equals(lastWait)) {
                lastWait = wait.why();
                LOG.info("[连锁挖] {}：等着，{}", trigger.toShortString(), wait.why());
            }
            return false;
        }
        if (readiness instanceof ChainKeyHold.Readiness.Chain chain) {
            batch = chain.batch();
            // 连没连锁、为什么没连，结果里看不出来：记一行，实机对得上屏幕上 FTB 面板跳没跳。
            LOG.info("[连锁挖] {}：按住连锁键，一批 {} 格", trigger.toShortString(), batch.size());
            return true;
        }
        if (readiness instanceof ChainKeyHold.Readiness.Single single) {
            singleReason = single.why();
            LOG.info("[连锁挖] {}：不连锁，{}", trigger.toShortString(), singleReason);
        }
        hold = null;
        return true;
    }

    @Override public void pause() {
        digging.pause();
        letGo();
    }

    @Override public void close() {
        digging.close();
        letGo();
    }

    // 停手时松开连锁键：插进来的事（吃东西、躲怪）挖别的不能带着连锁；回来时重新开一次。
    private void letGo() {
        if (hold != null) hold.release(current);
        hold = null;
        holdOpened = false;
        batch = List.of();
        droppingChain = false;
        if (stage == Stage.SETTLE || stage == Stage.RELEASE) stage = Stage.DIG;
    }

    @Override public Interruptibility interruptibility() {
        return digging.interruptibility();
    }

    @Override public String describe() {
        String at = trigger.toShortString();
        if (!batch.isEmpty()) return "连锁挖 " + at + "（一批 " + batch.size() + " 格）";
        return singleReason.isEmpty() ? "挖 " + at : "挖 " + at + "（不连锁：" + singleReason + "）";
    }
}
