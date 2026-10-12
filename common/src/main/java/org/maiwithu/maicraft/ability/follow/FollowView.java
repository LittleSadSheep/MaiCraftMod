// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随目标视图：锁定要跟的人，并逐刻报出他此刻的样子。只读，不动角色。
 *
 * <p>认人按 UUID：客户端的实体编号会被重用，编号还在而人换了就不跟；同一个人死了重生、换维度回来，
 * 编号变了而 UUID 没变，照样认得。生产实现读观察登记与世界，离线测试直接喂数值。
 */
interface FollowView {

    /**
     * 锁定这次要跟的人：观察编号按本会话的登记找回真实实体，玩家按名字找，记下他的 UUID。
     * 找不到（编号没登记过、名字不在附近）返回 null。
     */
    Locked lock(TickContext context, Target target);

    /** 他此刻的样子；看不见（走远、下线、死后消失、过了传送门）返回 null。 */
    Sighting observe(TickContext context);

    /** 看不见他时，他还在不在玩家列表里；跟的不是玩家时为 {@link Presence#NOT_A_PLAYER}。 */
    Presence presence(TickContext context);

    /**
     * 让路站哪：在他前进方向的两侧离他约 distance 格挑一格站得住的，两侧都没有再挑他身后；都没有为空。
     *
     * @param headingX 他前进方向的 x 分量（他不走时按他脸朝的方向）
     * @param headingZ 他前进方向的 z 分量
     */
    Optional<BlockPos> asideSpot(TickContext context, Sighting him, double headingX, double headingZ, int distance);

    /** 刚锁定时的目标：游戏实体编号、类型、名字（玩家名或类型）、位置与方位。 */
    record Locked(int entityId, String type, String name, WorldPosition position, String direction) {}

    /** 看不见他时他在不在线。 */
    enum Presence {
        /** 还在玩家列表里：人在，只是不在附近。 */
        ONLINE,
        /** 不在玩家列表里：下线了。 */
        OFFLINE,
        /** 跟的不是玩家，没有在线一说。 */
        NOT_A_PLAYER
    }

    /**
     * 他此刻的样子，以及我此刻和他的相对处境。
     *
     * @param position     他脚下的格与所在维度
     * @param ground       他脚下站得住的那一格：在地上就是脚下，在空中往下找（最多 64 格）
     * @param feet         他身子的精确位置：算他往哪走、走多快
     * @param eye          他眼睛的位置：停着时看他用
     * @param yaw          他脸朝哪（度）：他不走时让路按它算前方
     * @param direction    他在我的哪个方位（东边、北边、身边）
     * @param distance     我到他本体的三维距离
     * @param groundGap    我脚下到他脚下那一格的三维距离：起步与停下按它判，他在头顶飞时站到他正下方就算跟上
     * @param me           我身子的精确位置
     * @param sleeping     他躺在床上睡着
     * @param dead         他已经死了（倒地还没消失）
     * @param portal       他身子沾着的传送门方块 ID；没沾为 null
     * @param vehicleId    他坐着的载具的实体编号；没坐为 null
     * @param vehicleType  他坐着的载具类型；没坐为 null
     * @param sameVehicle  我和他坐在同一辆上
     * @param meRiding     我坐在什么上面（不管是不是他那辆）
     * @param inFight      他身边 6 格内有敌对生物，且他刚挨了打、刚挥了手、或那只刚挨了打
     */
    record Sighting(WorldPosition position, BlockPos ground, Vec3 feet, Vec3 eye, float yaw, String direction,
                    double distance, double groundGap, Vec3 me, boolean sleeping, boolean dead, String portal,
                    Integer vehicleId, String vehicleType, boolean sameVehicle, boolean meRiding, boolean inFight) {}
}
