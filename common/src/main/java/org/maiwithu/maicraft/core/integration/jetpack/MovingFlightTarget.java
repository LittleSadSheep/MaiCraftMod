package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/**
 * 提供会移动或仍在寻找中的飞行目标：更新位置、说明能否开始规划、是否已经接触并站稳，以及停止时是否另找落点。
 */
public interface MovingFlightTarget {
    boolean update(LocalPlayerContext context);
    Vec3 point();
    Vec3 velocity();
    boolean contact();
    boolean touchdown();
    /** Discovery can target an observed air corridor before selecting a real landing surface. */
    default boolean landingSelected() { return true; }
    /** False while a bounded observation/search slice is still choosing its next segment. */
    default boolean ready() { return true; }
    /** Static discovery points can use the existing release/reignite descent; moving decks cannot. */
    default boolean supportsFastDescent() { return false; }
    /** Keep discovering a nearby supported exit when cancellation occurs before any floor is known. */
    default boolean seekLandingOnStop() { return false; }
    /** Before native flight effects only: try a different observed landing face on the same identity. */
    default boolean nextLanding() { return false; }
    JetpackRoute.Space space(LocalPlayerContext context, LongSet forbidden);
    Map<String, Object> diagnostics();
}
