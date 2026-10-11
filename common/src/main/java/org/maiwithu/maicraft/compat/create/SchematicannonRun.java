// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

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
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 蓝图加农炮照蓝图施工（machine_run，file 写蓝图文件名，anchor 写原点）：找到身上写着这份文件的蓝图 →
 * 照蓝图工具摆好时那样把它摆到原点 → 加农炮没火药时备一件 → 点开加农炮，蓝图放进蓝图格、火药放进火药格 →
 * 按开动 → 关上界面看它放，直到报完工、卡住（缺料、没火药、太远、蓝图失效）、久无进展或到时限。
 * 料从加农炮旁边贴着的箱子里拿，缺什么如实写；到时限时加农炮接着放，不替它停。
 */
final class SchematicannonRun implements FullRun {

    static final String SCHEMATIC = CreateMachineCatalog.MOD_ID + ":schematic";
    private static final String GUNPOWDER = "minecraft:gunpowder";
    private static final int SYNC_WAIT_TICKS = 40;
    private static final int POLL_TICKS = 20;
    /** 这么久一格都没多放、也没报卡在哪，就当作停住了。 */
    private static final int NO_PROGRESS_TICKS = 20 * 60;
    /** 加农炮停下来的状态与给 LLM 的说法；类别：游戏事实（SchematicannonBlockEntity 的 statusMsg）。 */
    private static final Map<String, String> STALLS = Map.of(
            "noGunpowder", "火药用完了：往加农炮的火药格放火药",
            "targetOutsideRange", "蓝图离加农炮太远，超出射程：把加农炮挪近或换个原点",
            "targetNotLoaded", "要放的那片没加载：人走近一点",
            "schematicInvalid", "蓝图读不出来：回蓝图桌重写",
            "schematicErrored", "蓝图读的时候出错：回蓝图桌重写",
            "schematicExpired", "服务器上这份蓝图的文件没了：回蓝图桌重写",
            "schematicNotPlaced", "蓝图没摆好：给 anchor 再来一次");

    private final CreateCompat compat;
    private final PlayerServices services;
    private final BlockPos at;
    private final String file;
    private final BlockPos anchor;
    private final int rotation;
    private final int maxTicks;
    private final StepsInOrder steps;
    private final List<String> notes = new ArrayList<>();
    private int produced;
    private boolean finished;

    SchematicannonRun(CreateCompat compat, PlayerServices services, BlockPos at, String file, BlockPos anchor,
            int rotation, int maxSeconds, Permissions permissions) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.at = at.immutable();
        this.file = Objects.requireNonNull(file, "file");
        this.anchor = anchor;
        this.rotation = rotation;
        this.maxTicks = maxSeconds * 20;
        this.steps = new StepsInOrder("蓝图加农炮照 " + file + " 施工", List.of(
                new StepsInOrder.Step("摆好蓝图", Deploying::new),
                new StepsInOrder.Step("备火药", () -> gunpowder(permissions)),
                new StepsInOrder.Step("装蓝图、开动", () -> new Loading(permissions)),
                new StepsInOrder.Step("看加农炮放", Watching::new)));
    }

    @Override public ActionStatus tick(TickContext context) {
        return steps.tick(context);
    }

    @Override public int produced() {
        return produced;
    }

    @Override public boolean complete() {
        return finished;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    // 加农炮里没剩火药、身上也没有时备一件；料格里有没有火药开了界面才看得到，到时候再放。
    private Action gunpowder(Permissions permissions) {
        ClientLevel level = level();
        boolean fueled = level != null && compat.cannon(level, at).map(reading -> reading.fuel() > 0).orElse(false);
        if (fueled || carriedSlot(stack -> idOf(stack).equals(GUNPOWDER)) >= 0) return null;
        return services.needs().actionFor(new ItemRequest(WantedItem.ofItem(GUNPOWDER), 1, "蓝图加农炮的火药"), permissions);
    }

    /** 背包里（0~35 格）第一件合条件的东西在哪一格；没有为 -1。 */
    private int carriedSlot(Predicate<ItemStack> wanted) {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return -1;
        Inventory inventory = current.localPlayer().getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && wanted.test(stack)) return slot;
        }
        return -1;
    }

    private boolean ours(ItemStack stack) {
        return compat.schematicFile(stack).filter(file::equals).isPresent();
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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

    /** 找到写着这份文件的蓝图，照蓝图工具摆好时那样发一包摆到原点；已经摆在那儿就不动。 */
    private final class Deploying implements Action {
        private boolean sent;
        private int waited;

        @Override public ActionStatus tick(TickContext context) {
            int slot = carriedSlot(SchematicannonRun.this::ours);
            if (slot < 0) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有写着 " + file + " 的蓝图",
                        "先对蓝图桌 machine_run(item=create:schematic, file=" + file + ") 写一张"));
            }
            ItemStack stack = context.player().localPlayer().getInventory().getItem(slot);
            Optional<CreateSchematics.Placement> placed = compat.placement(stack);
            if (anchor == null) {
                return placed.isPresent() ? ActionStatus.done() : ActionStatus.failed(Problem.of(
                        Problem.Kind.INVALID_PARAMETER, "这张蓝图还没摆：给 anchor 说原点落在哪一格"));
            }
            if (placed.filter(now -> now.anchor().equals(anchor) && now.rotation() == rotation).isPresent()) {
                return ActionStatus.done();
            }
            if (!sent) {
                compat.deploy(slot, anchor, rotation);
                sent = true;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "发了摆蓝图的那一包，蓝图上的位置没变成要的样子"));
        }

        @Override public String describe() {
            return "把蓝图 " + file + " 摆到 " + (anchor == null ? "原处" : anchor.toShortString());
        }
    }

    /** 加农炮界面里的几步：容器侧 0 蓝图格、1 用完的蓝图、2 书、3 材料清单、4 火药格。 */
    private final class Loading extends CreateMenuWork {

        private enum Step { CLEAR, LOAD, FUEL, PLAY }

        private Step step = Step.CLEAR;
        private PendingMenuAction pending;
        private int waited;
        private boolean moved;

        Loading(Permissions permissions) {
            super(SchematicannonRun.this.services, SchematicannonRun.this.at, permissions, "蓝图加农炮");
        }

        @Override protected ActionStatus work(ClientLevel level, OpenedMenu menu, Optional<MenuContent.Reading> reading) {
            if (reading.isEmpty()) return waitOr("蓝图加农炮的界面开了，格子一直没同步过来");
            MenuContent.Reading now = reading.get();
            return switch (step) {
                case CLEAR -> clear(menu, now);
                case LOAD -> load(menu, now);
                case FUEL -> fuel(level, menu, now);
                case PLAY -> play(menu);
            };
        }

        // 蓝图格里是别的蓝图：先拿回背包，这一份才放得进去。
        private ActionStatus clear(OpenedMenu menu, MenuContent.Reading reading) {
            SlotSnapshot slot = inside(reading, 0);
            if (slot.isEmpty() || ours(slot.stack())) return next(Step.LOAD);
            if (moved) return waitOr("加农炮蓝图格里原有的蓝图拿不回背包（背包可能满了）");
            if (!menu.quickMove(containerSlot(reading, 0))) return ActionStatus.running();
            moved = true;
            return ActionStatus.progressed();
        }

        private ActionStatus load(OpenedMenu menu, MenuContent.Reading reading) {
            if (ours(inside(reading, 0).stack())) return next(Step.FUEL);
            if (moved) return waitOr("蓝图放不进加农炮的蓝图格");
            int slot = playerSlot(reading, SchematicannonRun.this::ours);
            if (slot < 0) return closeWith(Problem.of(Problem.Kind.NEED_ITEM, "身上没有写着 " + file + " 的蓝图"));
            if (!menu.quickMove(slot)) return ActionStatus.running();
            moved = true;
            return ActionStatus.progressed();
        }

        // 火药：加农炮里没剩、火药格空着、身上有，就整叠放进去；放进去几件写进说明。
        private ActionStatus fuel(ClientLevel level, OpenedMenu menu, MenuContent.Reading reading) {
            boolean fueled = compat.cannon(level, at).map(now -> now.fuel() > 0).orElse(false);
            if (fueled || !inside(reading, 4).isEmpty()) return next(Step.PLAY);
            if (moved) return waitOr("火药放不进加农炮的火药格");
            int slot = playerSlot(reading, stack -> idOf(stack).equals(GUNPOWDER));
            if (slot < 0) {
                notes.add("加农炮里没有火药、身上也没有，开动后会停在没火药");
                return next(Step.PLAY);
            }
            if (!menu.quickMove(slot)) return ActionStatus.running();
            moved = true;
            return ActionStatus.progressed();
        }

        // 按开动：加农炮读数变成开着才算按上。
        private ActionStatus play(OpenedMenu menu) {
            if (pending == null) {
                MenuConfirmation running = (context, sent) -> compat.cannon(context.level(), at)
                        .filter(CreateSchematics.CannonReading::running).isPresent()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
                Optional<PendingMenuAction> sent = menu.submitModAction("开动蓝图加农炮", () -> compat.pressCannon("play"),
                        running, SYNC_WAIT_TICKS);
                if (sent.isEmpty()) return ActionStatus.running();
                pending = sent.get();
                return ActionStatus.progressed();
            }
            if (!pending.terminal()) return ActionStatus.running();
            if (pending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED) return closeWith(null);
            return closeWith(Problem.of(Problem.Kind.REFUSED_BY_GAME, "按了开动，加农炮没开起来"));
        }

        private int playerSlot(MenuContent.Reading reading, Predicate<ItemStack> wanted) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot slot = reading.playerSnapshots().get(i);
                if (!slot.isEmpty() && wanted.test(slot.stack())) return reading.playerSlotIds().get(i);
            }
            return -1;
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

        @Override protected String doing() {
            return switch (step) {
                case CLEAR -> "拿出加农炮里原有的蓝图";
                case LOAD -> "把蓝图放进加农炮";
                case FUEL -> "给加农炮放火药";
                case PLAY -> "按开动";
            };
        }
    }

    /** 关上界面看加农炮放：报完工收场；卡在缺料、没火药、太远这些写进说明收场；久无进展或到时限也收场。 */
    private final class Watching implements Action {
        private long started = -1;
        private long lastGrowth;
        private int baseline = -1;

        @Override public ActionStatus tick(TickContext context) {
            long now = context.gameTick();
            if (started < 0) {
                started = now;
                lastGrowth = now;
            }
            if ((now - started) % POLL_TICKS != 0) return ActionStatus.running();
            Optional<CreateSchematics.CannonReading> reading = compat.cannon(context.player().level(), at);
            if (reading.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "加农炮不见了"));
            }
            CreateSchematics.CannonReading cannon = reading.get();
            if (baseline < 0) baseline = cannon.placed();
            int placed = Math.max(0, cannon.placed() - baseline);
            if (placed > produced) {
                produced = placed;
                lastGrowth = now;
            }
            if (cannon.status().equals("finished")) {
                finished = true;
                notes.add("加农炮报完工：放了 " + cannon.placed() + " 格");
                return ActionStatus.done();
            }
            String stall = cannon.status().equals("missingBlock") && cannon.missing().isPresent()
                    ? "缺 " + cannon.missing().get() + "：放进贴着加农炮的箱子里，它会接着放"
                    : STALLS.get(cannon.status());
            if (stall != null) {
                notes.add(stall + "（已放 " + cannon.placed() + " / " + cannon.toPlace() + " 格）");
                return ActionStatus.done();
            }
            if (!cannon.running() && !cannon.paused()) {
                notes.add("加农炮停了，状态 " + cannon.status());
                return ActionStatus.done();
            }
            if (now - lastGrowth >= NO_PROGRESS_TICKS) {
                notes.add("一分钟没多放一格，状态 " + cannon.status() + "（已放 " + cannon.placed() + " / " + cannon.toPlace() + " 格）");
                return ActionStatus.done();
            }
            if (now - started >= maxTicks) {
                notes.add("到了等待上限，加农炮还在放（已放 " + cannon.placed() + " / " + cannon.toPlace() + " 格）");
                return ActionStatus.done();
            }
            return ActionStatus.running();
        }

        // 看它放的时候手上没有进行中的动作：吃一口这类不急的事也能插进来，回来接着看。
        @Override public Interruptibility interruptibility() {
            return Interruptibility.BETWEEN_ACTIONS;
        }

        @Override public String describe() {
            return "看加农炮放（已放 " + produced + " 格）";
        }
    }
}
