package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.ref.WeakReference;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** One already-owned boat rescue may interact before vanilla reports this tick's ground contact. */
public final class BoatCatchWindow {
    private static BoatLandingAssist active;
    private static WeakReference<LocalPlayer> player=new WeakReference<>(null);
    private static long epoch,revision;
    private BoatCatchWindow() {}
    static void arm(BoatLandingAssist controller,LocalPlayerContext context) {
        active=controller; player=new WeakReference<>(context.player()); epoch=context.bodyEpoch(); revision=context.controlRevision();
    }
    static void clear(BoatLandingAssist controller) {
        if(active==controller) { active=null; player.clear(); }
    }
    public static void beforePositionPacket(LocalPlayer current) {
        var controller=active;
        if(controller==null) return;
        var actor=ClientRuntime.actor();
        if(player.get()!=current || !current.isAlive() || !actor.body().automationOwnsControls()) { clear(controller); return; }
        if(!DefaultBodyControlPort.permitsWorldMovement(net.minecraft.client.Minecraft.getInstance().screen)) return;
        try {
            actor.beginPositionPacketTick().ifPresent(context->{
                if(context.bodyEpoch()!=epoch || context.controlRevision()!=revision || !context.permitsNativeActions()) clear(controller);
                else controller.beforePositionPacket(context);
            });
        } catch(RuntimeException failure) { controller.catchWindowFailed(failure); clear(controller); }
    }
}
