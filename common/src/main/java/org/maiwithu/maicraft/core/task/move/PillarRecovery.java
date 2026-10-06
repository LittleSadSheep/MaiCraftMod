// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldDescentDrive;

/**
 * travel 收尾的自有垫柱回收：到达后把为登顶垫起的支撑柱从顶向下逐格拆回，
 * 身体随每格拆除自然降一格，最后落回垫柱前的地面。拆除走 build 域已验证的
 * 原生下拆驱动（对齐柱心 → 验证整柱退路 → 原生拆一格 → 落稳确认），本类只做
 * 柱列认领与逐格驱动链。
 *
 * <p>回收是尽力语义：下拆被否定或中断时立即停手，已确认拆掉的格子记账，
 * 剩余垫块按 RESIDUE 声明给调用方——回收失败不推翻「已到达」这个事实。
 */
final class PillarRecovery {

    enum Status { RUNNING, CLEAN, RESIDUE }

    /** 单条垫柱的高度上限；build 域整柱证明本身限 32 格，这里对齐同一量级。 */
    private static final int MAX_COLUMN_BLOCKS = 32;

    private final LocalPlayer player;
    /** 剩余待拆的自有垫块；键为世界格，值为认领时的实地方块状态，供整柱证明核对未被改动。 */
    private final LinkedHashMap<BlockPos, BlockState> owned = new LinkedHashMap<>();
    private final Predicate<BlockPos> permitted;
    private final LongSet forbidden;
    private final PlayerNav.ContextProvider walking;
    /** 真实拆除确认回调：驱动只按原生回执交账，这里据此从待拆清单除名。 */
    private final Consumer<BlockPos> confirmed = this::onConfirmed;

    private BuildScaffoldDescentDrive drive;
    private final List<BlockPos> recovered = new ArrayList<>();
    private Status status = Status.RUNNING;
    private String reason = "recovery_not_started";
    private int drivesFinished;
    private String lastDriveEvidence = "";

    /**
     * 认领一条垫柱并准备回收。{@code columnTopFirst} 为柱列格子、从脚下一格向下排列；
     * 各格的实地方块状态在此刻读取留底，之后任何改动都会让整柱证明如实拒绝。
     */
    PillarRecovery(LocalPlayer player, List<BlockPos> columnTopFirst, Predicate<BlockPos> permitted,
                   LongSet forbidden, PlayerNav.ContextProvider walking) {
        this.player = player;
        this.permitted = permitted;
        this.forbidden = forbidden;
        this.walking = walking;
        for (BlockPos at : columnTopFirst) {
            owned.put(at.immutable(), player.level().getBlockState(at));
        }
    }

    /**
     * 从本旅程放置记录里认领脚下正下方的连续自有柱：从脚下一格起向下逐格,
     * 遇到非自有格即停。travel 只回收到达脚下的登顶柱；途中留在别处的垫块
     * 由旅程地形账如实声明，不在此扩权乱挖。
     */
    static List<BlockPos> ownPillarUnder(java.util.Set<BlockPos> placed, BlockPos feet, int maxBlocks) {
        List<BlockPos> column = new ArrayList<>();
        BlockPos at = feet.below();
        while (column.size() < Math.min(maxBlocks, MAX_COLUMN_BLOCKS) && placed.contains(at)) {
            column.add(at);
            at = at.below();
        }
        return column;
    }

    /** 推进一格回收；终态（CLEAN / RESIDUE）后重复调用不再动作。 */
    Status tick() {
        if (status != Status.RUNNING) return status;
        if (owned.isEmpty()) return finish(Status.CLEAN, "scaffolding pillar fully recovered");
        BlockPos underfoot = player.blockPosition().below();
        if (!owned.containsKey(underfoot)) {
            // 剩余自有垫块不在脚下：身体已离开柱列，继续下拆会破坏非自有支撑，停手声明。
            return finish(Status.RESIDUE, "recovered_underfoot_column_body_left_own_pillar");
        }
        if (drive == null) {
            drive = new BuildScaffoldDescentDrive(player, underfoot, Map.copyOf(owned),
                    permitted, forbidden, walking, confirmed);
        }
        var result = drive.tick();
        switch (result) {
            case RUNNING -> { /* 单格拆除尚未收场，下一刻继续。 */ }
            case STEP_DONE -> {
                drivesFinished++;
                lastDriveEvidence = drive.evidence().toString();
                drive.stop();
                drive = null;
                if (owned.isEmpty()) return finish(Status.CLEAN, "scaffolding pillar fully recovered");
            }
            case UNAVAILABLE, FAILED -> {
                lastDriveEvidence = drive.evidence().toString();
                String detail = result + ":" + drive.reason();
                drive.stop();
                drive = null;
                return finish(Status.RESIDUE, detail);
            }
        }
        return status;
    }

    /** 任务被取消或收尾时停掉进行中的下拆；已确认的拆除由驱动结算交账。 */
    void stop() {
        if (drive != null) {
            lastDriveEvidence = drive.evidence().toString();
            drive.stop();
            drive = null;
        }
        if (status == Status.RUNNING) {
            status = Status.RESIDUE;
            reason = "recovery_interrupted";
        }
    }

    private void onConfirmed(BlockPos at) {
        owned.remove(at);
        recovered.add(at.immutable());
    }

    private Status finish(Status next, String detail) {
        status = next;
        reason = detail;
        return status;
    }

    Status status() { return status; }

    /** 回执证据：回收了哪些格、还剩哪些格、下拆驱动自身的证据原文。 */
    Map<String, Object> evidence() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.name());
        data.put("reason", reason);
        data.put("recovered_count", recovered.size());
        data.put("recovered", recovered.stream().map(BlockPos::toShortString).toList());
        data.put("residue", owned.keySet().stream().map(BlockPos::toShortString).toList());
        data.put("drives_finished", drivesFinished);
        if (!lastDriveEvidence.isEmpty()) data.put("descent_drive", lastDriveEvidence);
        return Map.copyOf(data);
    }

    /** 到达回执的收尾注记：回收成立或残留声明，都把格子点名列出供调用方复验。 */
    String note() {
        return switch (status) {
            case RUNNING, CLEAN -> recovered.isEmpty() ? ""
                    : " The scaffolding pillar I built to reach the target (" + recovered.size()
                    + " block(s) at " + recovered.getFirst().toShortString() + " and below)"
                    + " was dug back down after arrival; I am standing on the original ground again.";
            case RESIDUE -> " Note: " + owned.size() + " scaffolding block(s) placed en route remain"
                    + (owned.isEmpty() ? "" : " at " + owned.keySet().stream().map(BlockPos::toShortString)
                            .limit(6).reduce((a, b) -> a + "; " + b).orElse(""))
                    + " (recovery stopped: " + reason + "); dig them back or recycle them separately.";
        };
    }
}
