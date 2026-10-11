// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 开自己的载具去（travel 的 via=vehicle）：一段 = 走到驾驶位旁（上船、在甲板上走过去）→ 坐下接管 →
 * 开到目的地附近停稳 → 交还（人还坐着）。下船、走完最后一段由行程接着规划：人在船上时上下船那一段非它不可。
 * 开哪辆：给了 vehicle（s#）就是它；没给用角色正站在或坐在上面的那辆；都没有就答不出报价、写明要指定。
 * 报价前同样查操纵表：缺必需角色、车头没定就答不出，写明缺哪个。只在指定了 via=vehicle 或给了 vehicle 时出报价，
 * 别人没提时不擅自开船。
 */
public final class VehicleTravelMode implements TravelMode {

    static final TravelVia VIA = new TravelVia("vehicle", "开自己的载具（飞艇、车、船）：上船、开到目的地附近停稳，再下来走完");
    /** 估时：飞艇巡航、车、船每秒几格，起降与上下车另加几刻。 */
    static final double AIRSHIP_SPEED = 6;
    static final double CAR_SPEED = 4;
    static final double BOAT_SPEED = 3;
    static final int OVERHEAD_TICKS = 20 * 40;

    private final List<VehicleDriver> drivers;
    private final List<VehicleReader> readers;
    private final VehicleControls controls;
    private final Function<String, Optional<String>> structures;

    /** @param structures 观察编号 s# 换成结构身份 */
    public VehicleTravelMode(List<VehicleDriver> drivers, List<VehicleReader> readers, VehicleControls controls,
            Function<String, Optional<String>> structures) {
        this.drivers = List.copyOf(drivers);
        this.readers = List.copyOf(readers);
        this.controls = Objects.requireNonNull(controls, "controls");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    @Override public List<ParamSpec> options() {
        return List.of(ParamSpec.of("vehicle", ParamType.TEXT)
                .doc("只配 via=vehicle：开哪辆（物理结构的观察编号 s#）；不给用角色正站在或坐在上面的那辆").build());
    }

    // 只读：认出开哪辆、操纵表齐不齐，按路程与种类估时。
    @Override public void offer(TravelRequest request, OfferSink sink) {
        if (!VIA.name().equals(request.via()) && !request.options().containsKey("vehicle")) {
            sink.decline(Problem.Kind.UNSUPPORTED, "没指定开载具（via=vehicle）");
            return;
        }
        if (request.to() == null) {
            sink.decline(Problem.Kind.UNSUPPORTED, "开载具要有目的地");
            return;
        }
        Optional<String> structure = request.options().containsKey("vehicle")
                ? structures.apply(request.options().get("vehicle").trim())
                : readers.stream().map(VehicleReader::carrying).flatMap(Optional::stream).findFirst();
        if (structure.isEmpty()) {
            sink.decline(Problem.Kind.NOT_FOUND, request.options().containsKey("vehicle")
                    ? "vehicle 指的结构不在了（重新 observe 拿 s#）" : "没指定开哪辆：给 vehicle（s#），或先站到船上");
            return;
        }
        Optional<VehicleReader.View> view = readers.stream().map(reader -> reader.read(structure.get()))
                .flatMap(Optional::stream).findFirst();
        if (view.isEmpty()) {
            sink.decline(Problem.Kind.NOT_FOUND, "读不了这辆（离得太远，或没有能读它的联动）");
            return;
        }
        VehicleControls.ControlMap map = VehicleControls.merge(view.get().kind(), view.get().controls(),
                controls.stored(structure.get()));
        Problem notReady = DrivePlan.notReady(map);
        if (notReady != null) {
            sink.decline(notReady.kind(), notReady.message() + "（" + notReady.suggestion() + "）");
            return;
        }
        double distance = Math.sqrt(request.from().distSqr(request.to()));
        double speed = switch (view.get().kind()) {
            case "airship" -> AIRSHIP_SPEED;
            case "car" -> CAR_SPEED;
            default -> BOAT_SPEED;
        };
        long ticks = Math.round(distance / speed * 20) + OVERHEAD_TICKS;
        sink.offer(new TravelOffer(VIA, request.from(), request.to(), ticks, List.of(), List.of(),
                structure.get() + "|" + view.get().kind()));
    }

    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        String[] hint = offer.hint().split("\\|");
        String structure = hint[0];
        Optional<VehicleReader.View> view = readers.stream().map(reader -> reader.read(structure))
                .flatMap(Optional::stream).findFirst();
        VehicleControls.ControlMap map = VehicleControls.merge(hint[1],
                view.map(VehicleReader.View::controls).orElse(List.of()), controls.stored(structure));
        VehicleDriver.DriveRequest drive = new VehicleDriver.DriveRequest(structure,
                Vec3.atBottomCenterOf(offer.to()), false, null, hint[1], map.forward(), DrivePlan.roles(map),
                request.permissions());
        return new VehicleLeg(drivers, drive);
    }
}
