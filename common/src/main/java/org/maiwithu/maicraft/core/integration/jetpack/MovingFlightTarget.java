package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** A live landing identity; implementations retain logical coordinates, never an actor context. */
public interface MovingFlightTarget {
    boolean update(LocalPlayerContext context);
    Vec3 point();
    Vec3 velocity();
    boolean contact();
    boolean touchdown();
    /** Before native flight effects only: try a different observed landing face on the same identity. */
    default boolean nextLanding() { return false; }
    JetpackRoute.Space space(LocalPlayerContext context, LongSet forbidden);
    Map<String, Object> diagnostics();
}
