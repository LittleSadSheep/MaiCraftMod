// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import static org.maiwithu.maicraft.core.integration.ae2.Ae2ServerMenuFixture.field;

/** Real menu ownership/render gates and session transitions; the fixture does not claim a rendered Minecraft game. */
public final class Ae2ServerMenuPresentationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        visibleSameHostAndPostTransferFrameAreRequired();
        serverRouteStartsOnlyAfterNativeMenuOpening();
        everyServerJobRequestKeepsItsPhysicalTarget();
        System.out.println("Ae2ServerMenuPresentationTest: real opening, same-host GUI and receipt-bound presentation passed");
    }

    private static void visibleSameHostAndPostTransferFrameAreRequired() throws Exception {
        try (var f = new Ae2ServerMenuFixture()) {
            f.show(f.menu); var presentation = f.presentation();
            check(!presentation.ready(f.context()), "a menu object alone cannot replace its rendered opening dwell");
            f.awaitVisible(presentation);
            check(f.bridge.matchesFixedTerminalMenu(f.menu, f.cable, Direction.EAST)
                            && !f.bridge.matchesFixedTerminalMenu(f.menu, f.cable, Direction.WEST),
                    "the exact AE2 part identity matters even for two terminal faces on the same cable host");
            presentation.changed(f.context());
            for (int tick = 0; tick < 4; tick++) { f.world.nextTick(); check(!presentation.readyToClose(f.context()), "ticks cannot replace a frame showing the final inventory"); }
            MenuVisibility.rendered(f.minecraft.screen);
            check(presentation.readyToClose(f.context()), "confirmed inventory remains visible before cleanup");
            f.menu.setCarried(new ItemStack(Items.STICK));
            rejects(() -> presentation.ready(f.context()), "human cursor changes must stop another RPC"); f.menu.setCarried(ItemStack.EMPTY);
            var other = new Ae2ServerMenuFixture.Storage(8, f.cable.west); f.show(other);
            check(!presentation.owns(f.context()), "a replacement AE2 GUI is not the session's menu");
            rejects(() -> presentation.ready(f.context()), "replacement menu cannot authorize extraction");
            check(f.world.blockUses() == 0 && f.world.itemUses() == 0, "presentation checks never submit a second native extraction");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void serverRouteStartsOnlyAfterNativeMenuOpening() throws Exception {
        try (var f = new Ae2ServerMenuFixture()) {
            check(Ae2ServerSupply.available(), "server route must be advertised for this regression");
            check(f.world.level.getBlockEntity(f.position) == f.cable, "fixture must expose the actual client-level terminal identity");
            var session = new Ae2SupplySession(f.world.player, f.request, f.bridge);
            field(Ae2SupplySession.class, "fixedTarget").set(session, f.target);
            var phase = field(Ae2SupplySession.class, "phase"); phase.set(session, Enum.valueOf((Class) phase.getType(), "OPEN_FIXED"));
            var eye = f.world.player.getEyePosition(); var delta = f.target.hit().subtract(eye);
            f.world.player.setYRot(90); f.world.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
            session.tick(f.context());
            check(f.world.blockUses() == 1 && session.phase().equals("wait_open")
                            && field(Ae2SupplySession.class, "serverSupply").get(session) == null,
                    "advertised server assistance still opens the real fixed terminal before any RPC supply path: phase=" + session.phase() + ", uses=" + f.world.blockUses()
                            + ", outcome=" + field(Ae2SupplySession.class,"pendingTerminal").get(session));
            check(session.mustSettleBeforeSatisfiedCancellation(), "pending native menu opening must be settled before the parent cancels on inventory arrival");
            f.show(f.menu);
            for (int tick = 0; tick < 12 && !session.phase().equals("server_supply"); tick++) { f.next(); session.tick(f.context()); }
            check(session.phase().equals("server_supply") && f.world.blockUses() == 1, "same-host visible menu enters the server path without a second click");
            var supply = (Ae2ServerSupply) field(Ae2SupplySession.class, "serverSupply").get(session);
            var bodyMethod = Ae2ServerSupply.class.getDeclaredMethod("targetBody"); bodyMethod.setAccessible(true);
            var body = (com.google.gson.JsonObject) bodyMethod.invoke(supply);
            check(body.get("container_id").getAsInt() == 7 && body.get("side").getAsString().equals("east")
                            && body.getAsJsonObject("position").get("x").getAsInt() == 4,
                    "server requests bind the opened container and exact terminal side/position");
            check(f.sent.stream().noneMatch(v -> v.get("kind").getAsString().equals("request")), "opening/render waits cannot secretly extract through the server");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void serverFallbackRestoresAccess() throws Exception {
        try (var f = new Ae2ServerMenuFixture()) {
            f.show(f.menu); var presentation = f.presentation(); f.awaitVisible(presentation);
            var session = new Ae2SupplySession(f.world.player, f.request, f.bridge);
            var server = new Ae2ServerSupply(f.world.player, f.request, f.target, Set.of(), ignored -> 0, 7);
            field(Ae2ServerSupply.class, "terminal").set(server, new Ae2ServerSupply.Progress(Ae2ServerSupply.State.FALLBACK, "unsupported_operation", "untouched"));
            field(Ae2SupplySession.class, "serverSupply").set(session, server); field(Ae2SupplySession.class, "serverMenu").set(session, presentation);
            field(Ae2SupplySession.class, "terminalAccess").set(session, "server_fixed_terminal");
            field(Ae2SupplySession.class, "accessBeforeServer").set(session, "fixed_terminal");
            var fallback = field(Ae2SupplySession.class, "serverFallbackPhase"); fallback.set(session, Enum.valueOf((Class) fallback.getType(), "WAIT_REPOSITORY"));
            var tick = Ae2SupplySession.class.getDeclaredMethod("tickServerSupply", org.maiwithu.maicraft.client.actor.LocalPlayerContext.class);
            tick.setAccessible(true); tick.invoke(session, f.context());
            check(session.phase().equals("wait_repository") && field(Ae2SupplySession.class, "serverSupply").get(session) == null
                            && field(Ae2SupplySession.class, "terminalAccess").get(session).equals("fixed_terminal")
                            && f.world.player.containerMenu == f.menu && f.world.blockUses() == 0,
                    "untouched fallback continues in the existing GUI instead of reopening it or extracting twice");
        }
    }

    private static void everyServerJobRequestKeepsItsPhysicalTarget() throws Exception {
        try (var f = new Ae2ServerMenuFixture()) {
            f.show(f.menu);
            var supply = new Ae2ServerSupply(f.world.player, f.request, f.target, Set.of(), ignored -> 0, 7);
            var target = Ae2ServerSupply.class.getDeclaredMethod("targetBody"); target.setAccessible(true);
            var body = (com.google.gson.JsonObject) target.invoke(supply);
            var job = new Ae2ServerCraftJob(body, "exact", "network", 1);
            field(Ae2ServerCraftJob.class, "jobId").set(job, "job");
            var jobBody = Ae2ServerCraftJob.class.getDeclaredMethod("jobBody"); jobBody.setAccessible(true);
            var request = (com.google.gson.JsonObject) jobBody.invoke(job);
            check(request.get("container_id").getAsInt() == 7 && request.get("position").equals(body.get("position"))
                            && request.get("side").equals(body.get("side")) && request.get("job_id").getAsString().equals("job"),
                    "craft start/status/cancel must retain the originating physical terminal binding");
        }
    }
    private static void rejects(Runnable action, String message) { try { action.run(); throw new AssertionError(message); } catch (Ae2ProtocolException expected) { } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
