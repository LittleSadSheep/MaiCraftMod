// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 坐机械动力的电梯当一种出行方式（via=elevator）：楼里装了电梯就不爬楼。
 * 报价：附近有电梯、角色站在它的某一层（或就在轿厢里）、能叫得动它（轿厢就停在这层，或这层触点旁有呼梯按钮）、
 * 轿厢里有装置控制器能选层，就按"走到按钮 + 等轿厢 + 坐过去"估一份；楼层表没同步先要一次，下一刻再答。
 * 去哪层：给了 floor 按它（楼层名、top、bottom、up、down），没给按目的地的高度挑最近的一层。
 */
final class CreateElevatorMode implements TravelMode {

    static final TravelVia VIA = new TravelVia("elevator", "坐 Create 电梯换楼层：走到电梯口呼梯、进轿厢选层、到了走出去");
    static final String FLOOR = "floor";

    /** 找电梯的范围（格）。 */
    static final int SEARCH = 48;
    /** 估时：轿厢每挪一格多少刻、上下轿厢选层一共多少刻、走路每格多少刻。 */
    static final int TICKS_PER_BLOCK = 4;
    static final int BOARDING_TICKS = 60;
    static final double WALK_TICKS_PER_BLOCK = 20 / 4.3;
    /** 同一台电梯隔多久才再要一次楼层表。 */
    static final int FLOOR_REQUEST_EVERY = 40;

    private final CreateCompat compat;
    private final PlayerServices services;
    private final Map<Integer, Long> floorsAsked = new HashMap<>();

    CreateElevatorMode(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    @Override public List<ParamSpec> options() {
        return List.of(ParamSpec.of(FLOOR, ParamType.TEXT)
                .doc("只配 via=elevator：去哪一层，楼层名（observe 看电梯的楼层表）或 top / bottom / up / down；"
                        + "只换楼层时不给 target").build());
    }

    // 问报价：附近每台电梯各看一遍，能送的都报上；楼层表没同步的先要一次，下一刻再答。
    @Override public void offer(TravelRequest request, OfferSink sink) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null) return;
        BlockPos from = request.from();
        AABB region = new AABB(from).inflate(SEARCH);
        List<CreateElevators.Elevator> elevators = compat.elevators(level, player, region);
        if (elevators.isEmpty()) {
            sink.decline(Problem.Kind.UNSUPPORTED, "附近 " + SEARCH + " 格内没有 Create 电梯");
            return;
        }
        String option = request.options().get(FLOOR);
        boolean syncing = false;
        boolean offered = false;
        List<String> reasons = new ArrayList<>();
        for (CreateElevators.Elevator elevator : elevators) {
            if (elevator.floors().isEmpty()) {
                syncing |= askFloors(elevator, level.getGameTime());
                continue;
            }
            Optional<CreateElevators.Floor> here = ElevatorFloors.floorOf(elevator, from);
            if (here.isEmpty()) {
                reasons.add("不在电梯（轿厢在 " + BlockPos.containing(elevator.cabin().getCenter()).toShortString()
                        + "）的任何一层，也不在轿厢里");
                continue;
            }
            ElevatorFloors.Target target = ElevatorFloors.target(elevator, here.get(), option, request.to());
            if (target.problem() != null) {
                // 楼层说错了：参数错误，带上这台电梯实际的楼层表。
                sink.decline(Problem.Kind.INVALID_PARAMETER, target.problem());
                return;
            }
            if (target.floor().contactY() == here.get().contactY()) {
                reasons.add("要去的那一层就是现在这层（" + ElevatorFloors.describe(here.get()) + "）");
                continue;
            }
            if (elevator.controls().isEmpty()) {
                reasons.add("轿厢里没有装置控制器，选不了层");
                continue;
            }
            Optional<BlockPos> button = Optional.empty();
            boolean comesHere = elevator.carriesPlayer()
                    || elevator.currentContactY() == here.get().contactY() || elevator.targetContactY() == here.get().contactY();
            if (!comesHere) {
                button = nearest(ElevatorFloors.callButtons(level, elevator.contact(here.get())), from);
                if (button.isEmpty()) {
                    reasons.add("这一层（" + ElevatorFloors.describe(here.get()) + "）的触点旁没有呼梯按钮，轿厢停在别的层");
                    continue;
                }
            }
            long ticks = button.map(at -> Math.round(Math.sqrt(at.distSqr(from)) * WALK_TICKS_PER_BLOCK)).orElse(0L)
                    + (long) Math.abs(elevator.currentContactY() - here.get().contactY()) * TICKS_PER_BLOCK
                    + (long) Math.abs(target.floor().contactY() - here.get().contactY()) * TICKS_PER_BLOCK + BOARDING_TICKS;
            BlockPos to = BlockPos.containing(elevator.cabin().getCenter().x, target.floor().landingY(),
                    elevator.cabin().getCenter().z);
            sink.offer(new TravelOffer(VIA, from, to, ticks, List.of(), List.of(),
                    elevator.entityId() + "|" + target.floor().contactY()));
            offered = true;
        }
        if (offered || syncing) return;
        sink.decline(Problem.Kind.UNREACHABLE, String.join("；", reasons));
    }

    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        String[] hint = offer.hint().split("\\|");
        return new ElevatorRide(compat, services, request.permissions(), Integer.parseInt(hint[0]),
                Integer.parseInt(hint[1]));
    }

    // 楼层表空着：这台电梯隔一会要一次，还在等就是"这一刻还在看现场"。
    private boolean askFloors(CreateElevators.Elevator elevator, long now) {
        Long asked = floorsAsked.get(elevator.entityId());
        if (asked == null || now - asked >= FLOOR_REQUEST_EVERY) {
            compat.requestElevatorFloors(elevator.entityId());
            floorsAsked.put(elevator.entityId(), now);
        }
        return true;
    }

    private static Optional<BlockPos> nearest(List<BlockPos> cells, BlockPos from) {
        return cells.stream().min(Comparator.comparingDouble(cell -> cell.distSqr(from)));
    }
}
