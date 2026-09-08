package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.field;

/** The early catch and normal end-of-tick scheduler share one authority and one mutation budget. */
public final class PacketTickBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var h=new ActorControlTestHarness();
        check(h.actor.beginPositionPacketTick().isEmpty(),"an already-open actor context cannot be replaced by a nested packet callback");
        var early=h.context;
        early.claimMutation();
        field(ClientActorBoundary.class,"positionPacketTick").setBoolean(h.actor,true);
        var end=h.actor.beginTick().orElseThrow();
        check(end==early && end.tickRevision()==h.tick,"the normal callback reuses the pre-position context");
        check(!end.mutationAvailable(),"an early mount cannot gain another native action at the normal callback");
        try { early.claimMutation(); throw new AssertionError("a second mutation was accepted"); }
        catch(IllegalStateException expected) { }
        h.actor.endTick(end);
        check(h.actor.activeContext().isEmpty(),"normal endTick retires the borrowed context");
        var unavailable=new ActorControlTestHarness();
        unavailable.body.shutdown();
        field(ClientActorBoundary.class,"activeContext").set(unavailable.actor,null);
        check(unavailable.actor.beginPositionPacketTick().isEmpty(),"a packet callback cannot acquire automation from a human");
        System.out.println("PacketTickBudgetTest: passed");
    }
}
