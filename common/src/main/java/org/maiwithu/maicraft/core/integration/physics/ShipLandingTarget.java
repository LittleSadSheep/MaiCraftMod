package org.maiwithu.maicraft.core.integration.physics;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.integration.jetpack.MovingFlightTarget;

/** Follows one retained local deck face on one UUID, including rotation about the native pivot. */
public final class ShipLandingTarget implements MovingFlightTarget {
    private final UUID id;
    private StructureDeckGeometry.Surface site;
    private List<StructureDeckGeometry.Surface> candidates = List.of();
    private int candidateIndex;
    private Vec3 point, velocity = Vec3.ZERO;
    private boolean available, contact;
    private final SupportDwell support = new SupportDwell();
    private long lastTick = Long.MIN_VALUE;
    private String detail = "locating an observed physical structure";

    public ShipLandingTarget(UUID id) { this.id = java.util.Objects.requireNonNull(id); }

    @Override public boolean update(LocalPlayerContext context) {
        if (lastTick == context.tickRevision()) return available;
        lastTick = context.tickRevision();
        available = false; contact = false;
        var nativeContact = SableStructureBridge.contact(context.player());
        if (!nativeContact.known()) return unavailable("native vessel-contact API is unavailable");
        var ship = SableStructureBridge.find(context.level(), id);
        if (ship == null || !Boolean.TRUE.equals(ship.ready()) || ship.pose() == null || ship.storageBounds() == null)
            return unavailable("target structure unloaded or its native pose is unavailable");
        double width = context.player().getBbWidth() + .16, height = context.player().getBbHeight() + .08;
        try {
            if (site == null) {
                var geometry = StructureDeckGeometry.sample(context.level(), ship::isLoaded, ship.pose(),
                        ship.storageBounds(), ship.plotCenter(), ship.pose().toStorage(context.player().position()), width, height);
                var world = JetpackRoute.observed(context, LongSets.emptySet());
                var clear = geometry.surfaces().stream().filter(s -> world.clear(s.feet(), s.feet())).toList();
                var power = org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeAdapter.inspect(context);
                candidates = alternatives(rank(clear, ship.storageBounds().getCenter(), p -> power.controllable()
                        ? JetpackRoute.edgeTicks(context.player().position(),p,power) : p.distanceTo(context.player().position())));
                site = nativeContact.supportedBy(id) && context.player().onGround()
                        ? clear.stream().min(Comparator.comparingDouble(s -> s.feet().distanceToSqr(context.player().position()))).orElse(null)
                        : candidates.isEmpty() ? null : candidates.getFirst();
            } else site = StructureDeckGeometry.probe(context.level(), ship::isLoaded, ship.pose(),
                    ship.storageBounds(), site, width, height);
            if (site == null) return unavailable("no usable native deck face, or the selected deck changed");
            point = site.feet();
            velocity = ship.lastPose() == null ? Vec3.ZERO
                    : ship.pose().toWorld(site.storage()).subtract(ship.lastPose().toWorld(site.storage()));
            Vec3 localFeet = ship.pose().toStorage(context.player().position());
            contact = nativeContact.supportedBy(id) && context.player().onGround()
                    && localFeet.distanceToSqr(site.storage()) < 2.25;
            support.observe(lastTick,contact,localFeet);
            available = true;
            detail = touchdown() ? "native support on the requested vessel and stable local feet verified"
                    : contact ? "verifying stable native vessel contact" : "tracking the selected local deck face";
            return true;
        } catch (RuntimeException | LinkageError changed) { return unavailable("native deck geometry changed: " + changed.getClass().getSimpleName()); }
    }

    /** Prefer a broad usable patch and its interior, then reduce approach distance. */
    static StructureDeckGeometry.Surface choose(List<StructureDeckGeometry.Surface> sites, Vec3 center, Vec3 player) {
        return rank(sites,center,p -> p.distanceTo(player)).stream().findFirst().orElse(null);
    }
    static List<StructureDeckGeometry.Surface> rank(List<StructureDeckGeometry.Surface> sites, Vec3 center,
            java.util.function.ToDoubleFunction<Vec3> travelCost) {
        return sites.stream().sorted(Comparator.comparingDouble(site -> {
            int exposed = 0;
            for (Vec3 offset : List.of(new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,0,1),new Vec3(0,0,-1)))
                if (sites.stream().noneMatch(s -> s.storage().distanceToSqr(site.storage().add(offset)) < .0625)) exposed++;
            // Prefer room for horizontal braking, but never discard a narrower legal deck.
            return travelCost.applyAsDouble(site.feet()) + exposed * 24 + .001 * site.storage().distanceToSqr(center);
        })).toList();
    }
    static List<StructureDeckGeometry.Surface> alternatives(List<StructureDeckGeometry.Surface> ranked) {
        var result = new java.util.ArrayList<StructureDeckGeometry.Surface>();
        if (ranked.isEmpty()) return List.of();
        result.add(ranked.getFirst());
        ranked.stream().filter(s -> Math.abs(s.storage().y-ranked.getFirst().storage().y)>=.75)
                .findFirst().ifPresent(result::add);
        for (var candidate : ranked) {
            if (result.stream().anyMatch(s -> Math.abs(s.storage().y-candidate.storage().y)<.75
                    && Math.hypot(s.storage().x-candidate.storage().x,s.storage().z-candidate.storage().z)<3)) continue;
            result.add(candidate);
            if (result.size() == 3) break;
        }
        return List.copyOf(result);
    }
    @Override public boolean nextLanding() {
        if (candidateIndex + 1 >= candidates.size()) return false;
        site = candidates.get(++candidateIndex); support.reset(); available = false; contact = false;
        return true;
    }

    private boolean unavailable(String reason) {
        support.reset(); contact = false; detail = reason; return false;
    }
    @Override public Vec3 point() { return point; }
    @Override public Vec3 velocity() { return velocity; }
    @Override public boolean contact() { return contact; }
    @Override public boolean touchdown() { return available && support.ticks >= 8; }

    static final class SupportDwell {
        int ticks; long last = Long.MIN_VALUE; Vec3 previous;
        void observe(long tick, boolean contact, Vec3 localFeet) {
            if (tick == last) return;
            if (tick != last + 1) ticks = 0;
            ticks = contact && (previous == null || localFeet.distanceToSqr(previous) < .0225) ? ticks + 1 : 0;
            previous = localFeet; last = tick;
        }
        void reset() { ticks = 0; previous = null; last = Long.MIN_VALUE; }
    }

    @Override public JetpackRoute.Space space(LocalPlayerContext context, LongSet forbidden) {
        var main = JetpackRoute.observed(context, forbidden);
        Vec3 current = available ? point : null;
        return new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return main.clear(from, to); }
            public Vec3 landingBelow(Vec3 sample) {
                if (current != null && sample.y >= current.y - .02 && sample.y - current.y <= 64
                        && Math.hypot(sample.x - current.x, sample.z - current.z) < .45
                        && main.clear(sample, current)) return current;
                return main.landingBelow(sample);
            }
            public Map<String, Object> obstruction(Vec3 from, Vec3 to) { return main.obstruction(from,to); }
        };
    }
    @Override public Map<String, Object> diagnostics() {
        var out = new java.util.LinkedHashMap<String,Object>();
        out.put("structure_id",id.toString()); out.put("available",available); out.put("detail",detail);
        out.put("native_contact",contact); out.put("stable_contact_ticks",support.ticks); out.put("boarded",touchdown());
        out.put("velocity_blocks_per_tick",velocity.toString());
        out.put("candidate_index",candidateIndex); out.put("candidate_count",candidates.size());
        if (site != null) out.put("deck_block_id",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(site.state().getBlock()).toString());
        if (site != null) { out.put("deck_storage",site.storage().toString()); out.put("deck_world",point.toString()); }
        return out;
    }
}
