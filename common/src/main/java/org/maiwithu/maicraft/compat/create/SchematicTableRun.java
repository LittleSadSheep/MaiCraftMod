// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在蓝图桌上写一张蓝图（machine_run，file 写 schematics 目录下的 .nbt）：看文件传得了 → 拿一张空白蓝图 →
 * 走过去点开蓝图桌 → 输出格里有别的蓝图先拿回背包 → 空白蓝图放进输入格 → 照界面上的上传按钮开传 →
 * 等输出格出现写着这份文件的蓝图 → 拿回背包 → 关上界面。写出来的蓝图之后交给蓝图加农炮。
 */
final class SchematicTableRun implements FullRun {

    static final String EMPTY = CreateMachineCatalog.MOD_ID + ":empty_schematic";
    /** 界面开了等格子同步、快速移动之后等它挪过去，各最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;
    /** 开传之后等蓝图桌说"在传了"最多这么多刻。 */
    private static final int START_TIMEOUT_TICKS = 60;

    private final CreateCompat compat;
    private final String file;
    private final int maxTicks;
    private final StepsInOrder steps;
    private final List<String> notes = new ArrayList<>();
    private int produced;

    SchematicTableRun(CreateCompat compat, PlayerServices services, BlockPos at, String file, int maxSeconds,
            Permissions permissions) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.file = Objects.requireNonNull(file, "file");
        this.maxTicks = maxSeconds * 20;
        this.steps = new StepsInOrder("在蓝图桌上写蓝图 " + file, List.of(
                new StepsInOrder.Step("看蓝图文件", this::checkFile),
                new StepsInOrder.Step("拿一张空白蓝图", () -> services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(EMPTY), 1, "在蓝图桌上写蓝图"), permissions)),
                new StepsInOrder.Step("在蓝图桌上写", () -> new Writing(services, at, permissions))));
    }

    @Override public ActionStatus tick(TickContext context) {
        return steps.tick(context);
    }

    @Override public int produced() {
        return produced;
    }

    @Override public boolean complete() {
        return produced > 0;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    // 文件不在、不是压缩过的 .nbt、超过服务器上限都是游戏事实，开传前就说，不白跑一趟。
    private Action checkFile() {
        Optional<String> problem = compat.uploadProblem(file);
        if (problem.isEmpty()) return null;
        return failing(Problem.of(Problem.Kind.INVALID_PARAMETER, problem.get(),
                "蓝图文件放在游戏目录的 schematics 下，用 Create 的蓝图与笔或 design 导出 .nbt"));
    }

    /** 一刻就以这个问题收场的动作。 */
    static Action failing(Problem problem) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(problem);
            }

            @Override public String describe() {
                return problem.message();
            }
        };
    }

    @Override public void pause() {
        steps.pause();
    }

    @Override public void close() {
        steps.close();
    }

    @Override public Interruptibility interruptibility() {
        return steps.interruptibility();
    }

    @Override public String describe() {
        return steps.describe();
    }

    /** 蓝图桌界面里的几步：容器侧第 0 格是输入（空白蓝图），第 1 格是输出（写好的蓝图）。 */
    private final class Writing extends CreateMenuWork {

        private enum Step { CLEAR, INPUT, UPLOAD, WAIT, TAKE }

        private Step step = Step.CLEAR;
        private PendingMenuAction pending;
        private int waited;
        private boolean moved;

        Writing(PlayerServices services, BlockPos at, Permissions permissions) {
            super(services, at, permissions, "蓝图桌");
        }

        @Override protected ActionStatus work(ClientLevel level, OpenedMenu menu, Optional<MenuContent.Reading> reading) {
            if (reading.isEmpty()) return waitOr("蓝图桌的界面开了，格子一直没同步过来");
            MenuContent.Reading now = reading.get();
            return switch (step) {
                case CLEAR -> clear(menu, now);
                case INPUT -> input(menu, now);
                case UPLOAD -> upload(level, menu);
                case WAIT -> await(now);
                case TAKE -> take(menu, now);
            };
        }

        // 输出格里已经有这份蓝图就直接拿；有别的先拿回背包，免得写好的那张出不来。
        private ActionStatus clear(OpenedMenu menu, MenuContent.Reading reading) {
            SlotSnapshot output = inside(reading, 1);
            if (output.isEmpty()) return next(Step.INPUT);
            if (written(output)) return next(Step.TAKE);
            if (moved) return waitOr("蓝图桌输出格里原有的蓝图拿不回背包（背包可能满了）");
            if (!menu.quickMove(containerSlot(reading, 1))) return ActionStatus.running();
            moved = true;
            return ActionStatus.progressed();
        }

        // 输入格空着就把一张空白蓝图放进去（整叠快速移动，多的留在输入格里下次用）。
        private ActionStatus input(OpenedMenu menu, MenuContent.Reading reading) {
            if (!inside(reading, 0).isEmpty()) return next(Step.UPLOAD);
            if (moved) return waitOr("空白蓝图放不进蓝图桌的输入格");
            int slot = carried(reading);
            if (slot < 0) return closeWith(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有空白蓝图"));
            if (!menu.quickMove(slot)) return ActionStatus.running();
            moved = true;
            return ActionStatus.progressed();
        }

        // 照界面上的上传按钮开传：蓝图桌说"在传了"或输出格已经出现这份蓝图就算开传了。
        private ActionStatus upload(ClientLevel level, OpenedMenu menu) {
            if (pending == null) {
                MenuConfirmation started = (context, sent) -> compat.uploading(context.level(), at).isPresent()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
                Optional<PendingMenuAction> sent = menu.submitModAction("上传蓝图 " + file, () -> compat.startUpload(file),
                        started, START_TIMEOUT_TICKS);
                if (sent.isEmpty()) return ActionStatus.running();
                pending = sent.get();
                return ActionStatus.progressed();
            }
            if (!pending.terminal()) return ActionStatus.running();
            if (pending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED && compat.uploading(level, at).isEmpty()) {
                // 传得很快的小文件可能等不到"在传"就已经写好了：交给下一步看输出格。
                notes.add("开传后没看到蓝图桌显示在传");
            }
            return next(Step.WAIT);
        }

        // 等输出格出现写着这份文件的蓝图：传完才有；到时限没出现如实说。
        private ActionStatus await(MenuContent.Reading reading) {
            if (written(inside(reading, 1))) {
                produced = 1;
                return next(Step.TAKE);
            }
            if (++waited <= maxTicks) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK, "等了 " + maxTicks / 20 + " 秒，蓝图桌的输出格没出现写着 " + file + " 的蓝图"));
        }

        // 写好的蓝图拿回背包；拿不回来（背包满）就留在输出格，如实说。
        private ActionStatus take(OpenedMenu menu, MenuContent.Reading reading) {
            produced = 1;
            if (inside(reading, 1).isEmpty()) return closeWith(null);
            if (moved) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                notes.add("写好的蓝图留在蓝图桌的输出格里，没拿回背包（背包可能满了）");
                return closeWith(null);
            }
            if (!menu.quickMove(containerSlot(reading, 1))) return ActionStatus.running();
            moved = true;
            return ActionStatus.progressed();
        }

        private ActionStatus next(Step following) {
            step = following;
            waited = 0;
            moved = false;
            return ActionStatus.progressed();
        }

        private ActionStatus waitOr(String stuck) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK, stuck));
        }

        private boolean written(SlotSnapshot slot) {
            return !slot.isEmpty() && compat.schematicFile(slot.stack()).filter(file::equals).isPresent();
        }

        // 背包里空白蓝图在哪一格（界面格号）；没有为 -1。
        private int carried(MenuContent.Reading reading) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot slot = reading.playerSnapshots().get(i);
                if (!slot.isEmpty() && BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString().equals(EMPTY)) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        @Override protected String doing() {
            return switch (step) {
                case CLEAR -> "清蓝图桌的输出格";
                case INPUT -> "把空白蓝图放进蓝图桌";
                case UPLOAD, WAIT -> "上传蓝图 " + file;
                case TAKE -> "拿回写好的蓝图";
            };
        }
    }
}
