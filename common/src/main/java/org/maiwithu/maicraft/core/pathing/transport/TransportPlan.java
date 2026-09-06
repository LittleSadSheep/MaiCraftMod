package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorTravel;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

/** Rank only observed capabilities; selecting an offer does not claim its corridor is verified. */
final class TransportPlan {
    record Offer(String mode, BlockPos destination, double estimatedTicks, Supplier<TransportSession> create) {}
    record Options(List<Offer> offers, List<String> unavailable) {}

    static Options prepare(LocalPlayerContext context, NavGoal goal, TransportTargets targets,
                           TransportMode mode, LongSet forbidden) {
        List<Offer> offers = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (targets.destinations().isEmpty()) unavailable.add("no supported, unobstructed landing satisfies the destination; " + targets.diagnostic());
        var floors = new HashSet<Integer>();
        for (var destination : targets.destinations()) {
            if (mode != TransportMode.ELEVATOR && mode != TransportMode.GROUND) {
                var flight = JetpackFlightSession.probe(context, destination.landingPoint(), forbidden);
                if (flight.available()) offers.add(new Offer("jetpack", destination.feet(), flight.estimatedTicks(),
                        () -> new JetpackFlightSession(destination.landingPoint(), forbidden)));
                else if (unavailable.size() < 8) unavailable.add("jetpack: " + flight.reason());
            }
            if (mode != TransportMode.JETPACK && mode != TransportMode.GROUND
                    && floors.add(destination.feet().getY())) {
                elevator(context, destination.feet(), forbidden, offers, unavailable);
            }
        }
        // A distant destination may be outside the loaded view. A registered elevator can still
        // bring the body to that floor, where the original navigation goal is re-evaluated.
        if (offers.isEmpty() && mode != TransportMode.JETPACK && mode != TransportMode.GROUND) {
            for (BlockPos hint : elevatorHints(goal, context.player().blockPosition())) {
                if (floors.add(hint.getY())) elevator(context, hint, forbidden, offers, unavailable);
            }
        }
        offers.sort(Comparator.comparingDouble(Offer::estimatedTicks));
        return new Options(List.copyOf(offers), unavailable.stream().distinct().toList());
    }

    static List<BlockPos> elevatorHints(NavGoal goal, BlockPos origin) {
        var pending = new ArrayDeque<NavGoal>(); pending.add(goal);
        List<BlockPos> result = new ArrayList<>();
        int examined = 0;
        while (!pending.isEmpty() && examined++ < 256 && result.size() < 32) {
            NavGoal next = pending.removeFirst();
            if (next instanceof NavGoal.Composite composite) {
                composite.members.stream().limit(Math.max(0, 256 - examined - pending.size())).forEach(pending::addLast);
            } else if (next instanceof NavGoal.YLevel level) {
                result.add(new BlockPos(origin.getX(), level.level, origin.getZ()));
            } else if (next instanceof NavGoal.Exact || next instanceof NavGoal.GetToBlock
                    || next instanceof NavGoal.Adjacent || next instanceof NavGoal.NearGround
                    || next instanceof NavGoal.Near || next instanceof NavGoal.MineStance
                    || next instanceof NavGoal.MineColumn) result.add(next.center());
        }
        return result.stream().distinct().sorted(Comparator.comparingDouble(origin::distSqr)).toList();
    }

    private static void elevator(LocalPlayerContext context, BlockPos destination, LongSet forbidden,
                                 List<Offer> offers, List<String> unavailable) {
        Map<String, Object> probe = CreateElevatorTravel.probe(context, destination);
        if (Boolean.TRUE.equals(probe.get("available"))) {
            double ticks = probe.get("estimatedTicks") instanceof Number n ? n.doubleValue() : 200;
            offers.add(new Offer("elevator", destination, Double.isFinite(ticks) ? ticks : 200,
                    () -> new CreateElevatorTravel(destination, forbidden)));
        } else if (unavailable.size() < 8) unavailable.add("elevator: " + probe.getOrDefault("reason", "no observed service"));
    }
}
