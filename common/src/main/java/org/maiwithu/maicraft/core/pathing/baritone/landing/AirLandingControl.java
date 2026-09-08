package org.maiwithu.maicraft.core.pathing.baritone.landing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/** One horizontal controller shared by air steering and its short reachability prediction. */
public final class AirLandingControl {
    private AirLandingControl() { }
    private static Vec3 input(Vec3 position, Vec3 velocity, Vec3 target) {
        double x = ((target.x-position.x)*.25-velocity.x*.91)/.02;
        double z = ((target.z-position.z)*.25-velocity.z*.91)/.02;
        double scale = Math.max(1,Math.hypot(x,z));
        return new Vec3(x/scale,0,z/scale);
    }
    public static BodyControlPort.Movement movement(LocalPlayer player, BlockPos feet, float yaw, boolean sneak) {
        var command = input(player.position(),player.getDeltaMovement(),Vec3.atBottomCenterOf(feet));
        double angle = Math.toRadians(yaw);
        return new BodyControlPort.Movement((float)(-command.x*Math.sin(angle)+command.z*Math.cos(angle)),
                (float)(command.x*Math.cos(angle)+command.z*Math.sin(angle)),false,sneak,false);
    }
    static boolean reachable(LocalPlayer player, BlockPos feet) {
        double surface = feet.getY()-1 + baritone.pathing.movement.CollisionGeometry.supportHeight(player.level(),feet.below());
        double gravity = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY);
        if (!Double.isFinite(surface) || gravity <= 0 || player.getY() < surface-.01) return false;
        Vec3 position = player.position(), velocity = player.getDeltaMovement(), target = Vec3.atBottomCenterOf(feet);
        for (int tick=0;tick<200;tick++) {
            var command = input(position,velocity,target);
            velocity = velocity.add(command.scale(.02));
            var next = position.add(velocity);
            var body = player.getBoundingBox().move(next.subtract(player.position()));
            if (next.y <= surface) body = body.move(0,surface+.001-next.y,0);
            for(double x:new double[]{body.minX,body.maxX}) for(double z:new double[]{body.minZ,body.maxZ})
                if(!player.level().isLoaded(BlockPos.containing(x,body.minY,z))) return false;
            if (!player.level().noCollision(player,body)) return false;
            if (next.y <= surface) return Math.hypot(next.x-target.x,next.z-target.z) < .55;
            position = next; velocity = new Vec3(velocity.x*.91,(velocity.y-gravity)*.98,velocity.z*.91);
        }
        return false;
    }
}
