package org.maiwithu.maicraft.mcp;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;
import sun.misc.Unsafe;

/** One request waits for bounded tick slices, then returns that completed snapshot without resetting it. */
public final class NavigationOverviewTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var unsafeField=Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Unsafe memory=(Unsafe)unsafeField.get(null);
        var player=(LocalPlayer)memory.allocateInstance(LocalPlayer.class);
        var level=(ClockLevel)memory.allocateInstance(ClockLevel.class);
        field(player,"level",level); field(player,"position",new Vec3(.5,100,.5));
        var overview=new NavigationOverview();
        var result=overview.prepare(player).toCompletableFuture();
        check(!result.isDone(),"preparing a large overview must not scan the world synchronously");
        var view=new RegionalTerrain.View() {
            public boolean known(Vec3 point) { return true; }
            public Vec3 surfaceBelow(Vec3 point,int depth) { return point.y+80<=depth ? new Vec3(point.x,-80,point.z) : null; }
            public boolean visible(Vec3 from,Vec3 to) { return true; }
            public String material(Vec3 surface) { return "minecraft:smooth_stone"; }
        };
        overview.advance(view);
        check(!result.isDone(),"one small slice must not pretend the whole distant region has been scanned");
        for(int tick=0;tick<300 && !result.isDone();tick++) overview.advance(view);
        check(result.isDone() && !result.isCompletedExceptionally(),"the same request completes as later client ticks add evidence");
        var json=overview.describe(player);
        check(json.get("complete").getAsBoolean() && json.get("radius").getAsInt()==128 && json.toString().contains("minecraft:smooth_stone"),
                "return the completed wide material snapshot, not a restarted empty scan");
        check(overview.prepare(player).toCompletableFuture().isDone(),"a fresh completed snapshot is immediately reusable");
        var disconnected=new NavigationOverview();
        var waiting=disconnected.prepare(player).toCompletableFuture(); disconnected.tick(null);
        check(waiting.isCompletedExceptionally(),"world loss ends a pending observation rather than leaving its request hanging");
        System.out.println("NavigationOverviewTest: passed");
    }
    private static void field(Object owner,String name,Object value) throws Exception {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try { var field=type.getDeclaredField(name); field.setAccessible(true); field.set(owner,value); return; }
            catch(NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static final class ClockLevel extends ClientLevel {
        private ClockLevel() { super(null,null,null,null,0,0,null,null,false,0); }
        public long getGameTime() { return 0; }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
