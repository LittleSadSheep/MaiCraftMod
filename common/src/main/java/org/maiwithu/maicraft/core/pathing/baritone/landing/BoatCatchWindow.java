package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.ref.WeakReference;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * 在玩家位置包发出前，把一次已登记的落地上船机会交回对应控制器，尽量抓住很短的乘船时机。
 * 玩家、身体版本或控制权改变就作废旧机会；不能让上一具身体的请求继续操作现在的玩家。
 */
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
