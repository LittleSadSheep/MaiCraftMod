// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSessionRolloverTest.Fixture;

/** Real router/dispatcher expiry with independent client and server clocks; every replacement read gets a new ID. */
public final class ProductionSessionIdleExpiryTest {
    private static final String READ = "machine.snapshot", WRITE = "machine.configure";
    private ProductionSessionIdleExpiryTest() {}
    public static void main(String[] args) {
        idleConstructionRenewsBeforeItsNextOperation();
        expiredReadGetsOneIndependentObservation();
        repeatedExpiryStopsTheSameLogicalRead();
        changedOwnershipCannotRefreshAnExpiredRead();
        expiredMutationNeverRenewsOrReplays();
        System.out.println("ProductionSessionIdleExpiryTest: idle lease renewal, asynchronous expiry recovery and bounded read/mutation fencing passed");
    }

    private static void idleConstructionRenewsBeforeItsNextOperation() {
        var ticking = new Fixture(); ticking.welcome();
        for (int tick = 0; tick < 6001; tick++) ticking.advance(true);
        check(ticking.requests.isEmpty() && ticking.hellos == 2, "Construction-only ticks did not renew the idle scope");
        ticking.read(); check(ticking.reads == 1, "First post-construction operation failed");
        var gap = new Fixture(); gap.welcome(); gap.tick += 6001; gap.renew();
        check(gap.slot.call(READ, new JsonObject(), false) == null && !gap.slot.pending(), "Delayed welcome was treated as unavailable");
        gap.welcome(); gap.read(); check(gap.reads == 1, "A 6001-tick client gap prevented a fresh scope");
    }

    private static void expiredReadGetsOneIndependentObservation() {
        var fixture = expiredRead();
        check(fixture.slot.call(READ, new JsonObject(), false) == null && !fixture.slot.pending(), "Expired read was not released for a fresh observation");
        fixture.router.observe(++fixture.tick);
        check(fixture.router.renegotiating(READ), "Server-clock expiry did not start renewal");
        check(fixture.slot.call(READ, new JsonObject(), false) == null, "Independent observation bypassed its welcome");
        fixture.welcome(); fixture.read();
        check(fixture.reads == 1 && fixture.requests.size() == 2, "Expired read was replayed or reported as an executed observation");
        check(((Number) fixture.slot.report().get("server_tick")).longValue() > fixture.tick,
                "Receipt substituted the client clock for the actual leading server clock");
    }

    private static void repeatedExpiryStopsTheSameLogicalRead() {
        var fixture = expiredRead(); fixture.slot.call(READ, new JsonObject(), false);
        fixture.advance(true); // New welcome, with no replacement request submitted yet.
        check(fixture.slot.call(READ, new JsonObject(), false) == null, "Replacement read did not queue");
        fixture.serverOffset += 6001; fixture.advance(true);
        rejects(() -> fixture.slot.call(READ, new JsonObject(), false), "production_request_session_expired");
        check(fixture.requests.size() == 2 && fixture.reads == 0, "Repeated scope expiry restarted the logical retry allowance");
    }

    private static void changedOwnershipCannotRefreshAnExpiredRead() {
        for (String change : new String[]{"binding", "authority", "revocation", "channel"}) {
            var fixture = expiredRead();
            switch (change) {
                case "binding" -> fixture.router.bind(1, 2, "minecraft:overworld", 1, true, ++fixture.tick);
                case "authority" -> fixture.router.control(2, true);
                case "revocation" -> fixture.router.control(1, false);
                case "channel" -> { fixture.available = false; fixture.router.observe(++fixture.tick); }
                default -> throw new AssertionError(change);
            }
            rejects(() -> fixture.slot.call(READ, new JsonObject(), false), "production_request_session_expired");
            check(fixture.requests.size() == 1, "Expired read acquired a new identity after " + change);
        }
    }

    private static void expiredMutationNeverRenewsOrReplays() {
        var fixture = new Fixture(); fixture.welcome(); fixture.serverOffset = 6001;
        fixture.slot.call(WRITE, new JsonObject(), true); fixture.advance(true);
        rejects(() -> fixture.slot.call(WRITE, new JsonObject(), true), "production_effect_uncertain");
        fixture.tick += 6001; fixture.router.observe(fixture.tick);
        check(fixture.hellos == 1 && fixture.requests.size() == 1 && fixture.writes == 0,
                "An expired mutation was replayed or permitted idle scope renewal");
    }

    private static Fixture expiredRead() {
        var fixture = new Fixture(); fixture.welcome(); fixture.serverOffset = 6001;
        fixture.slot.call(READ, new JsonObject(), false); fixture.advance(true);
        return fixture;
    }
    private static void rejects(Runnable action, String code) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IllegalArgumentException | IllegalStateException rejected) {
            check(rejected.getMessage().contains(code), "Unexpected failure: " + rejected.getMessage());
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
