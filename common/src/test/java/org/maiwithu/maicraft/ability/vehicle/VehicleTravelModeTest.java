// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 开自己的载具去：没指定 via=vehicle 不出报价；不知道开哪辆、操纵表不齐都答不出并写明；齐了给报价；
 * 这一段开到了算做完，半路停下以卡住结束让行程接着规划。
 */
class VehicleTravelModeTest {

    @TempDir
    Path folder;

    /** 一辆站在上面的替身飞艇：回路推出动力、升力，转向未定。 */
    private static final VehicleReader ABOARD = new VehicleReader() {
        @Override public Optional<View> read(String structureId) {
            return Optional.of(new View(structureId, "", "airship", true, List.of(), List.of(
                    new Input("打字机:W", "a + b", 1, List.of(), "power", ""),
                    new Input("打字机:空格", "a + c", 1, List.of(), "lift", "")),
                    new State(false, 0, 0, 0, 0, "grounded", "self"), ""));
        }

        @Override public Optional<String> carrying() {
            return Optional.of("ship-uuid");
        }
    };

    /** 一个替身驾驶办法：够得着驾驶位，开一次按给定的到没到。 */
    private static VehicleDriver driver(boolean arrives) {
        return new VehicleDriver() {
            @Override public Optional<WorldPosition> boardAt(String structureId) { return Optional.empty(); }
            @Override public Optional<Run> test(TestRequest request) { return Optional.empty(); }
            @Override public Optional<Run> drive(DriveRequest request) {
                Report report = new Report(request.structureId(), "坐在驾驶座上", List.of(), true, true, "",
                        new Drove(List.of(), "", 200, 3, arrives, arrives ? 2 : 80, arrives, 20));
                return Optional.of(new Run() {
                    @Override public Report report() { return report; }
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.done();
                    }
                    @Override public String describe() { return "开"; }
                });
            }
        };
    }

    private VehicleControls controls() {
        return new VehicleControls(new DocumentStore(folder.resolve("world.db")), "w");
    }

    private static TravelRequest request(String via, Map<String, String> options) {
        return new TravelRequest(new BlockPos(0, 64, 0), new BlockPos(300, 64, 0), "minecraft:overworld", 2,
                Permissions.DEFAULT, options, via);
    }

    /** 收报价的替身：记下报价或拒绝的原因。 */
    private static final class Answers implements OfferSink {
        final List<TravelOffer> offers = new ArrayList<>();
        final List<String> declined = new ArrayList<>();

        @Override public void offer(TravelOffer offer) { offers.add(offer); }
        @Override public void decline(Problem.Kind kind, String reason) { declined.add(kind + " " + reason); }
    }

    @Test
    void 没指定开载具不出报价_操纵表不齐答不出() {
        VehicleTravelMode mode = new VehicleTravelMode(List.of(driver(true)), List.of(ABOARD), controls(), id -> Optional.empty());
        Answers quiet = new Answers();
        mode.offer(request(null, Map.of()), quiet);
        assertTrue(quiet.offers.isEmpty());
        Answers missing = new Answers();
        mode.offer(request("vehicle", Map.of()), missing);
        assertTrue(missing.offers.isEmpty());
        assertTrue(missing.declined.getFirst().contains("偏航"), missing.declined.toString());
        Answers gone = new Answers();
        mode.offer(request("vehicle", Map.of("vehicle", "s9")), gone);
        assertTrue(gone.declined.getFirst().startsWith("NOT_FOUND"), gone.declined.toString());
    }

    @Test
    void 齐了给报价_开到了这一段做完_半路停下以卡住结束() {
        VehicleControls controls = controls();
        controls.assign("ship-uuid", Map.of("打字机:A", "yaw_left"), "+z");
        VehicleTravelMode mode = new VehicleTravelMode(List.of(driver(true)), List.of(ABOARD), controls, id -> Optional.empty());
        Answers answers = new Answers();
        TravelRequest request = request("vehicle", Map.of());
        mode.offer(request, answers);
        TravelOffer offer = answers.offers.getFirst();
        assertEquals("vehicle", offer.via().name());
        assertTrue(offer.estimatedTicks() > 0);
        TravelLeg leg = mode.start(request, offer);
        assertInstanceOf(ActionStatus.Done.class, leg.tick(null), "够得着驾驶位就直接开，开到了算做完");
        assertTrue(leg.summary().contains("开到了"), leg.summary());

        VehicleTravelMode stopsShort = new VehicleTravelMode(List.of(driver(false)), List.of(ABOARD), controls, id -> Optional.empty());
        TravelLeg stopped = stopsShort.start(request, offer);
        ActionStatus status = stopped.tick(null);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.STUCK, failed.problem().kind());
        assertTrue(failed.problem().message().contains("80"), failed.problem().message());
    }
}
