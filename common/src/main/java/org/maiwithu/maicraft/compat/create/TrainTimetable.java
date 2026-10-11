// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.vehicle.spi.TrainScheduler;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 给一趟机械动力列车定时刻表：认出坐在列车操纵台座位上的生物（列车长；没有就开不了，照实说）→ 走到它跟前 →
 * 时刻表拿到主手（身上没有就去拿一张）→ 按条目写进去、读回核对 → 拿着它右键列车长交过去（手里那张被收走就是交上了）。
 * 列车原来有时刻表时列车长不收：空手右键列车长先把旧的取下来（回到背包），再写一遍交上去，只取一次。
 * 列车按表暂停着的时候空手点是让它继续、取不下旧表，照实说。
 */
final class TrainTimetable implements TrainScheduler {

    /** 时刻表物品。 */
    static final String SCHEDULE = "create:schedule";

    private final CreateCompat compat;
    private final PlayerServices services;

    TrainTimetable(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<Run> schedule(Request request) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        if (level == null) return Optional.empty();
        Entity car = level.getEntity(request.carEntityId());
        if (car == null) return Optional.empty();
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(car.getType()).toString();
        if (!type.equals("create:carriage_contraption")) return Optional.empty();
        return Optional.of(new Writing(request));
    }

    /** 一次定时刻表的运行。 */
    private final class Writing implements Run {

        private enum Step { FIND, APPROACH, ITEM, WRITE, HAND, EMPTY, TAKE }

        private final Request request;
        private final CreateRailways.Timetable wanted;
        private Step step = Step.FIND;
        private Action current;
        private Entity conductor;
        private long stepStart = Long.MIN_VALUE;
        private boolean sent;
        private boolean written;
        private boolean replaced;
        private boolean handed;
        private List<String> readBack = List.of();
        private Report report;

        Writing(Request request) {
            this.request = request;
            List<CreateRailways.Stop> stops = new ArrayList<>();
            for (Stop stop : request.stops()) stops.add(new CreateRailways.Stop(stop.station(), stop.conditions()));
            this.wanted = new CreateRailways.Timetable(stops, request.cyclic());
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext context = services.context().get();
            ClientLevel level = context == null ? null : context.level();
            LocalPlayer player = context == null ? null : context.localPlayer();
            if (level == null || player == null) return stop(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，定不了时刻表"));
            long now = level.getGameTime();
            if (stepStart == Long.MIN_VALUE) stepStart = now;
            if (current != null) {
                ActionStatus status = current.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                current.close();
                current = null;
                return after(status, now);
            }
            return switch (step) {
                case FIND -> find(level, now);
                case APPROACH -> approach(now);
                case ITEM -> item(player, now);
                case WRITE -> write(player, now);
                case HAND -> hand(player);
                case EMPTY -> empty(player, now);
                case TAKE -> take(player);
            };
        }

        // 列车长：坐在这节车厢操纵台座位上的生物（玩家不算）。没有就开不了这趟车的时刻表。
        private ActionStatus find(ClientLevel level, long now) {
            List<Integer> found = compat.conductors(level, request.carEntityId());
            Entity first = found.isEmpty() ? null : level.getEntity(found.getFirst());
            if (first == null) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "列车操纵台座位上没有生物（列车长）",
                        "让一只生物坐到列车操纵台旁的座位上（拴绳牵过去、引诱……），再定时刻表"));
            }
            conductor = first;
            return go(Step.APPROACH, now);
        }

        private ActionStatus approach(long now) {
            current = services.approach().toward(ApproachTarget.ofEntity(conductor.getBoundingBox()), request.permissions());
            return ActionStatus.progressed();
        }

        // 时刻表拿到主手：主手就是就不动，身上有就换上来，都没有就去拿一张。
        private ActionStatus item(LocalPlayer player, long now) {
            if (isSchedule(player.getMainHandItem())) return go(Step.WRITE, now);
            Optional<Action> move = services.toMainhand().moveToMainhand(SCHEDULE);
            current = move.orElseGet(() -> services.needs().actionFor(
                    new ItemRequest(WantedItem.ofItem(SCHEDULE), 1, "定列车时刻表"), request.permissions()));
            return ActionStatus.progressed();
        }

        // 写进去：发一次，读回和要的一样才算写好；两秒还不一样照实说。
        private ActionStatus write(LocalPlayer player, long now) {
            if (!sent) {
                compat.writeTimetable(wanted);
                sent = true;
            }
            CreateRailways.Timetable held = compat.heldTimetable(player);
            if (held != null) readBack = lines(held);
            if (held != null && same(held, wanted)) {
                written = true;
                return go(Step.HAND, now);
            }
            if (now - stepStart >= 40) {
                return stop(Problem.of(Problem.Kind.STUCK, "时刻表写了两秒，读回还不是要的样子：" + String.join("；", readBack)));
            }
            return ActionStatus.running();
        }

        // 拿着它右键列车长：手里那张被收走就是交上了；不收（多半是原来有表）就先空手取下旧的，只取一次。
        private ActionStatus hand(LocalPlayer player) {
            int before = count(player);
            current = services.interactions().useEntity(conductor,
                    context -> context.localPlayer() != null && count(context.localPlayer()) < before
                            ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
            return ActionStatus.progressed();
        }

        // 取旧表要空手：主手腾空。
        private ActionStatus empty(LocalPlayer player, long now) {
            if (player.getMainHandItem().isEmpty()) return go(Step.TAKE, now);
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isEmpty()) return stop(Problem.of(Problem.Kind.INVENTORY_FULL, "腾不出空手取下旧时刻表"));
            current = empty.get();
            return ActionStatus.progressed();
        }

        // 空手右键列车长取下旧表：背包里多一张时刻表就是取下来了。
        private ActionStatus take(LocalPlayer player) {
            int before = count(player);
            current = services.interactions().useEntity(conductor,
                    context -> context.localPlayer() != null && count(context.localPlayer()) > before
                            ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
            return ActionStatus.progressed();
        }

        // 子动作结束：走到了去拿表；拿到了去写；交上了做完；交不上去取旧的；取下旧的再拿表重写。
        private ActionStatus after(ActionStatus status, long now) {
            boolean failed = status instanceof ActionStatus.Failed;
            switch (step) {
                case APPROACH -> {
                    if (failed) return stop(((ActionStatus.Failed) status).problem());
                    return go(Step.ITEM, now);
                }
                case ITEM -> {
                    if (failed) return stop(((ActionStatus.Failed) status).problem());
                    return go(Step.WRITE, now);
                }
                case HAND -> {
                    if (!failed) {
                        handed = true;
                        return finish("时刻表交给了列车长");
                    }
                    if (replaced) {
                        return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME, "取下旧表后列车长还是不收新的时刻表"));
                    }
                    return go(Step.EMPTY, now);
                }
                case EMPTY -> {
                    if (failed) return stop(((ActionStatus.Failed) status).problem());
                    return go(Step.TAKE, now);
                }
                case TAKE -> {
                    if (failed) {
                        return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME, "列车长不收新的时刻表，空手也取不下旧的"
                                + "（列车按表暂停着时空手点是让它继续）"));
                    }
                    replaced = true;
                    return go(Step.ITEM, now);
                }
                default -> {
                    return failed ? stop(((ActionStatus.Failed) status).problem()) : ActionStatus.progressed();
                }
            }
        }

        private ActionStatus go(Step next, long now) {
            step = next;
            stepStart = now;
            sent = false;
            return ActionStatus.progressed();
        }

        private ActionStatus stop(Problem problem) {
            writeReport(problem.message());
            return ActionStatus.failed(problem);
        }

        private ActionStatus finish(String note) {
            writeReport(note);
            return ActionStatus.done();
        }

        private void writeReport(String note) {
            String who = conductor == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(conductor.getType()).toString();
            report = new Report(written, readBack, replaced, handed, who, note);
        }

        @Override public Report report() {
            return report;
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) {
                current.close();
                current = null;
            }
            if (report == null) writeReport("定时刻表被收掉了");
        }

        @Override public Interruptibility interruptibility() {
            return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
        }

        @Override public String describe() {
            return switch (step) {
                case FIND -> "找列车长";
                case APPROACH -> "走到列车长跟前";
                case ITEM -> "拿时刻表";
                case WRITE -> "写时刻表";
                case HAND -> "把时刻表交给列车长";
                case EMPTY -> "腾空手";
                case TAKE -> "空手取下旧时刻表";
            };
        }
    }

    // 读回的和要的一样：站名、等的条件、循环都对上。
    static boolean same(CreateRailways.Timetable held, CreateRailways.Timetable wanted) {
        return held.cyclic() == wanted.cyclic() && held.stops().equals(wanted.stops());
    }

    static List<String> lines(CreateRailways.Timetable timetable) {
        List<String> lines = new ArrayList<>();
        for (CreateRailways.Stop stop : timetable.stops()) {
            lines.add(stop.station() + "（" + (stop.conditions().isEmpty() ? "不等" : String.join("、", stop.conditions())) + "）");
        }
        if (!timetable.stops().isEmpty()) lines.add(timetable.cyclic() ? "循环" : "不循环");
        return lines;
    }

    private static boolean isSchedule(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(SCHEDULE);
    }

    // 身上（背包加副手）有几张时刻表。
    private static int count(LocalPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isSchedule(stack)) total += stack.getCount();
        }
        return total;
    }
}
