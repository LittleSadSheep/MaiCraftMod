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
    /** 选定实际着陆面前，探索阶段可先以前方已观察到的空气走廊为目标。 */
    default boolean landingSelected() { return true; }
    /** 有界观察或搜索切片仍在选择下一段时返回 false。 */
    default boolean ready() { return true; }
    /** 静态探索点可使用现有的释放并重新点火下降流程；移动甲板不能这样处理。 */
    default boolean supportsFastDescent() { return false; }
    /** 尚未确认地面支撑前若发生取消，仍继续寻找附近有支撑的出口。 */
    default boolean seekLandingOnStop() { return false; }
    /** 仅在原生飞行效果开始前，才允许对同一目标身份尝试另一已观察着陆面。 */
    default boolean nextLanding() { return false; }
    JetpackRoute.Space space(LocalPlayerContext context, LongSet forbidden);
    Map<String, Object> diagnostics();
}
