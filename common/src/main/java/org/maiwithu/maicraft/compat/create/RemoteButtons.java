// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按机械动力的遥控器（无线遥控器）：vehicle_drive(test) 的 {@code 遥控器:上}、{@code 遥控器:跳}……
 * 遥控器拿到主手 → 每条输入按住给定刻数（每 5 刻再发一次"还按着"，收端 30 刻收不到就当松开）→ 松开。
 * 遥控的是什么都行（载具、门、闸）；遥控器不回报收端动没动，看效果要自己 observe，或用无线红石打字机试按看载具。
 * 输入里有一条不是遥控器的就不归它管。
 */
final class RemoteButtons implements VehicleDriver {

    /** 遥控器物品。 */
    static final String REMOTE = "create:linked_controller";
    /** 按住期间隔几刻再发一次"还按着"。 */
    static final int KEEPALIVE = 5;

    /** 遥控器的六个键：名字 → 序号（前、后、左、右、跳、潜行）。 */
    static final Map<String, Integer> BUTTONS = Map.ofEntries(
            Map.entry("上", 0), Map.entry("前", 0), Map.entry("up", 0), Map.entry("forward", 0),
            Map.entry("下", 1), Map.entry("后", 1), Map.entry("down", 1), Map.entry("back", 1),
            Map.entry("左", 2), Map.entry("left", 2),
            Map.entry("右", 3), Map.entry("right", 3),
            Map.entry("跳", 4), Map.entry("跳跃", 4), Map.entry("jump", 4),
            Map.entry("潜行", 5), Map.entry("sneak", 5));

    private final CreateCompat compat;
    private final PlayerServices services;

    RemoteButtons(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<WorldPosition> boardAt(String structureId) {
        return Optional.empty();
    }

    @Override public Optional<Run> test(TestRequest request) {
        if (request.inputs().isEmpty() || !request.inputs().stream().allMatch(press -> press.control().startsWith("遥控器"))) {
            return Optional.empty();
        }
        return Optional.of(new Pressing(request));
    }

    /** 遥控器上这个输入是第几个键；不认得为 -1。 */
    static int button(String control) {
        int colon = Math.max(control.indexOf(':'), control.indexOf('：'));
        if (colon < 0) return -1;
        Integer found = BUTTONS.get(control.substring(colon + 1).trim().toLowerCase(Locale.ROOT));
        return found == null ? -1 : found;
    }

    /** 逐条按住再松开。 */
    private final class Pressing implements Run {

        private enum Step { HAND, PRESS, HOLD }

        private final TestRequest request;
        private final List<Tried> tried = new ArrayList<>();
        private Step step = Step.HAND;
        private Action current;
        private int index;
        private long pressedAt;
        private int held = -1;
        private Report report;

        Pressing(TestRequest request) {
            this.request = request;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext context = services.context().get();
            LocalPlayer player = context == null ? null : context.localPlayer();
            if (player == null || context.level() == null) return stop(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，按不了遥控器"));
            long now = context.level().getGameTime();
            if (current != null) {
                ActionStatus status = current.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                current.close();
                current = null;
                if (status instanceof ActionStatus.Failed failed) return stop(failed.problem());
                step = Step.PRESS;
                return ActionStatus.progressed();
            }
            return switch (step) {
                case HAND -> hand(player);
                case PRESS -> press(player, now);
                case HOLD -> hold(player, now);
            };
        }

        // 遥控器拿到主手：主手就是不动，身上有就换上来，没有照实说。
        private ActionStatus hand(LocalPlayer player) {
            if (isRemote(player.getMainHandItem()) || isRemote(player.getOffhandItem())) {
                step = Step.PRESS;
                return ActionStatus.progressed();
            }
            Optional<Action> move = services.toMainhand().moveToMainhand(REMOTE);
            if (move.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NEED_ITEM, "身上没有遥控器（create:linked_controller）", "先 obtain 一个，绑好键再按"));
            }
            current = move.get();
            return ActionStatus.progressed();
        }

        // 下一条：认不出的键跳过写明；认得的按下去。
        private ActionStatus press(LocalPlayer player, long now) {
            if (index >= request.inputs().size()) return finish("按完了");
            Press input = request.inputs().get(index);
            int button = button(input.control());
            if (button < 0) {
                tried.add(new Tried(input.control(), input.ticks(), false, true, "", null,
                        "认不出是遥控器的哪个键（上、下、左、右、跳、潜行）", List.of()));
                index++;
                return ActionStatus.progressed();
            }
            compat.remoteButtons(List.of(button), true);
            held = button;
            pressedAt = now;
            step = Step.HOLD;
            return ActionStatus.progressed();
        }

        // 按住：每 5 刻再发一次还按着；到了松开，接着下一条。
        private ActionStatus hold(LocalPlayer player, long now) {
            Press input = request.inputs().get(index);
            if (!isRemote(player.getMainHandItem()) && !isRemote(player.getOffhandItem())) {
                return stop(Problem.of(Problem.Kind.STUCK, "按着的时候遥控器不在手里了"));
            }
            if (now - pressedAt >= input.ticks()) {
                release();
                tried.add(new Tried(input.control(), input.ticks(), true, true,
                        "按住 " + input.ticks() + " 刻再松开（遥控器不回报收端动没动，看效果要自己看）", null, "", List.of()));
                index++;
                step = Step.PRESS;
                return ActionStatus.progressed();
            }
            if ((now - pressedAt) % KEEPALIVE == 0) compat.remoteButtons(List.of(held), true);
            return ActionStatus.running();
        }

        private void release() {
            if (held >= 0) {
                compat.remoteButtons(List.of(held), false);
                held = -1;
            }
        }

        private ActionStatus stop(Problem problem) {
            release();
            writeReport(problem.message());
            return ActionStatus.failed(problem);
        }

        private ActionStatus finish(String note) {
            writeReport(note);
            return ActionStatus.done();
        }

        private void writeReport(String note) {
            report = new Report(request.structureId(), "拿着遥控器", tried, held < 0, true, note, null);
        }

        @Override public Report report() {
            return report;
        }

        // 被打断：松开按着的键，这一条回来从头按。
        @Override public void pause() {
            if (current != null) current.pause();
            release();
            if (step == Step.HOLD) step = Step.PRESS;
        }

        @Override public void close() {
            if (current != null) {
                current.close();
                current = null;
            }
            release();
            if (report == null) writeReport("按遥控器被收掉了");
        }

        @Override public Interruptibility interruptibility() {
            if (current != null) return current.interruptibility();
            return step == Step.HOLD ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return step == Step.HAND ? "拿遥控器" : index < request.inputs().size() ? "按 " + request.inputs().get(index).control() : "按遥控器";
        }
    }

    private static boolean isRemote(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(REMOTE);
    }
}
