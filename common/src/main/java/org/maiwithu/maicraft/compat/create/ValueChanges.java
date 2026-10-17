// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 改机械动力设置框的动作。过滤格：手持那样东西（或空着手）点过滤格画框的那一处，Create 把手里的东西设成过滤；
 * 数值、选项、过滤格的件数：走到够得着的地方，发 Create 设置界面关上时发的那一包，等同步回来的值对上。
 * 每一下只发一次，等不到同步如实说，不再发第二下。
 */
final class ValueChanges {

    /** 发了改值的包以后等同步回来最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;
    /** 过滤格点击框的半边长：取 Create 判点中半径的六成，点在框里一定在半径以内。 */
    private static final double SPOT_SHARE = 0.6;

    private final CreateCompat compat;
    private final PlayerServices services;

    ValueChanges(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 改一项设置的动作；键不认、值写得不对时给一个按原因失败的动作，不认的键给空。 */
    Optional<Action> change(ClientLevel level, BlockPos at, String key, String value, Permissions permissions) {
        CreateValues.Boxes boxes = compat.valueBoxes(level, at);
        Optional<CreateValueSettings.Target> target = CreateValueSettings.target(boxes, key);
        if (target.isEmpty()) return Optional.empty();
        CreateValueSettings.Target found = target.get();
        if (found.value() != null) {
            CreateValueSettings.Encoded encoded = CreateValueSettings.encode(found.value(), value);
            if (encoded.problem() != null) return Optional.of(failing(Problem.Kind.INVALID_PARAMETER, encoded.problem()));
            int netId = found.value().netId();
            return Optional.of(new SendsValue(at, "把「" + found.value().label() + "」改成 " + value, permissions,
                    netId, encoded, now -> now.values().stream().filter(box -> box.netId() == netId)
                            .anyMatch(box -> CreateValueSettings.shown(box).equals(value.toLowerCase(Locale.ROOT)))));
        }
        CreateValues.FilterBox filter = found.filter();
        if (found.amount()) {
            CreateValueSettings.Encoded encoded = CreateValueSettings.encodeAmount(filter, value);
            if (encoded.problem() != null) return Optional.of(failing(Problem.Kind.INVALID_PARAMETER, encoded.problem()));
            return Optional.of(new SendsValue(at, "把「" + filter.label() + "」的件数改成 " + value, permissions,
                    filter.netId(), encoded, now -> sameFilter(now, filter)
                            .filter(box -> CreateValueSettings.amountValue(box).equals(value.trim())).isPresent()));
        }
        return Optional.of(setFilter(at, filter, value, permissions));
    }

    /**
     * 改无线红石信号终端的动作：频率格是手持那样东西（或空着手）点那一格；收发是空着手潜行右键终端，
     * 潜行时 Create 不当成点频率格。键不认、值不合给空。
     */
    Optional<Action> changeLink(ClientLevel level, BlockPos at, String key, String value, Permissions permissions) {
        Optional<CreateStations.LinkReading> reading = compat.link(level, at);
        if (reading.isEmpty()) return Optional.empty();
        Optional<Integer> index = LinkSettings.frequencyIndex(key);
        if (index.isPresent()) {
            if (index.get() >= reading.get().spots().size()) {
                return Optional.of(failing(Problem.Kind.NOT_POSSIBLE_HERE, "这个频率格现在没有画出来能点的地方"));
            }
            int slot = index.get();
            return Optional.of(clickWithHeld(at, reading.get().spots().get(slot), "把第 " + (slot + 1) + " 个频率格设成 " + value,
                    value, permissions, now -> compat.link(now, at)
                            .filter(link -> LinkSettings.itemValue(link.frequencies().get(slot)).equals(value)).isPresent()));
        }
        if (!key.equals(LinkSettings.MODE)) return Optional.empty();
        if (!value.equals(LinkSettings.RECEIVE) && !value.equals(LinkSettings.TRANSMIT)) {
            return Optional.of(failing(Problem.Kind.INVALID_PARAMETER, "link.mode 只认 receive 或 transmit：" + value));
        }
        boolean receiver = value.equals(LinkSettings.RECEIVE);
        Action toggle = new HandThenClick(services, "把无线红石换成" + (receiver ? "收信号" : "发信号"), at, new AABB(at),
                permissions, () -> mainhand().isEmpty() ? Optional.empty() : services.toMainhand().emptyMainhand(),
                () -> context -> compat.link(context.level(), at).filter(link -> link.receiver() == receiver).isPresent()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
        // 先照常走到跟前，到了再按住潜行点：一路潜行着走太慢，也不是人会做的。
        return Optional.of(new StepsInOrder("把无线红石换成" + (receiver ? "收信号" : "发信号"), List.of(
                new StepsInOrder.Step("走到终端跟前", () -> services.approach().toward(ApproachTarget.ofBlock(at), permissions)),
                new StepsInOrder.Step("空着手潜行右键", () -> services.sneak().sneaking(toggle)))));
    }

    // 设过滤格：空着手点是清空，手持东西点是设成它；身上没有那样东西就按缺东西失败，不拿着别的去点。
    private Action setFilter(BlockPos at, CreateValues.FilterBox filter, String value, Permissions permissions) {
        if (filter.spots().isEmpty()) {
            return failing(Problem.Kind.NOT_POSSIBLE_HERE, "「" + filter.label() + "」现在没有画出来能点的地方");
        }
        return clickWithHeld(at, filter.spots().getFirst(), "把「" + filter.label() + "」设成 " + value, value, permissions,
                now -> sameFilter(compat.valueBoxes(now, at), filter)
                        .filter(box -> CreateValueSettings.filterValue(box.filter()).equals(value)).isPresent());
    }

    // 手持那样东西（empty 是空着手）点方块上的一处小框：框取 Create 判点中半径的六成，点在框里一定算点中；
    // 身上没有那样东西就按缺东西失败，不拿着别的去点。读回对上才算做成。
    private Action clickWithHeld(BlockPos at, CreateValues.Spot spot, String what, String value, Permissions permissions,
            Predicate<ClientLevel> done) {
        double half = spot.radius() * SPOT_SHARE;
        AABB box = new AABB(spot.center().subtract(half, half, half), spot.center().add(half, half, half))
                .intersect(new AABB(at));
        boolean clear = value.equals(CreateValueSettings.EMPTY);
        return new HandThenClick(services, what, at, box, permissions,
                () -> clear ? (mainhand().isEmpty() ? Optional.empty() : services.toMainhand().emptyMainhand())
                        : mainhand().equals(value) ? Optional.empty()
                        : services.toMainhand().moveToMainhand(value)
                                .or(() -> Optional.of(failing(Problem.Kind.NEED_ITEM, "身上没有 " + value))),
                () -> context -> done.test(context.level())
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    // 同一个过滤格：网络编号一样、每面一格的还要是同一面。
    private static Optional<CreateValues.FilterBox> sameFilter(CreateValues.Boxes boxes, CreateValues.FilterBox wanted) {
        return boxes.filters().stream()
                .filter(box -> box.netId() == wanted.netId() && Objects.equals(box.side(), wanted.side()))
                .findFirst();
    }

    private String mainhand() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return "";
        var held = current.localPlayer().getMainHandItem();
        return held.isEmpty() ? "" : CreateValueSettings.filterValue(held);
    }

    // 做不成的动作：一刻就按原因失败，不去点、不发包。
    private static Action failing(Problem.Kind kind, String reason) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(kind, reason));
            }

            @Override public String describe() {
                return reason;
            }
        };
    }

    /**
     * 走到够得着的地方，发一次改值的包，等同步回来的值对上：Create 只收离方块不远的玩家发的设置包，
     * 所以先走近；对不上就是服务器没收或改成了别的值，如实说。
     */
    private final class SendsValue implements Action {
        private final BlockPos at;
        private final String what;
        private final Permissions permissions;
        private final int netId;
        private final CreateValueSettings.Encoded encoded;
        private final Predicate<CreateValues.Boxes> done;
        private Action approach;
        private boolean sent;
        private int waited;

        SendsValue(BlockPos at, String what, Permissions permissions, int netId, CreateValueSettings.Encoded encoded,
                Predicate<CreateValues.Boxes> done) {
            this.at = at.immutable();
            this.what = what;
            this.permissions = permissions;
            this.netId = netId;
            this.encoded = encoded;
            this.done = done;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (approach == null) approach = services.approach().toward(ApproachTarget.ofBlock(at), permissions);
            if (!sent) {
                ActionStatus walking = approach.tick(context);
                if (walking instanceof ActionStatus.Failed failed) return failed;
                if (!(walking instanceof ActionStatus.Done)) return walking;
                // 包里的那一面不能空；数值框不分面，按顶面发。
                compat.sendValue(at, netId, Direction.UP, encoded.row(), encoded.value());
                sent = true;
                return ActionStatus.progressed();
            }
            if (done.test(compat.valueBoxes(context.player().level(), at))) return ActionStatus.done();
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, what + "：发了改值的包，同步回来的值一直没对上"));
        }

        @Override public void close() {
            if (approach != null) approach.close();
        }

        @Override public Interruptibility interruptibility() {
            return sent ? Interruptibility.WORKING : approach == null ? Interruptibility.WORKING : approach.interruptibility();
        }

        @Override public String describe() {
            return sent ? what + "，等同步回来" : what;
        }
    }
}
