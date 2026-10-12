// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随里除了走路以外的几样身体动作：看着他、坐上他那辆、下车、跟着过门、帮他打。
 *
 * <p>走路直接用走到；这里的每一样都是玩家行为层已有的做法拼起来的：看人是镜头的平滑转头，
 * 坐车是走过去右键，过门是出行的跨维度段，帮打是自卫与 fight 共用的战斗引擎。生产实现接真实客户端，测试换替身。
 */
interface FollowMoves {

    /** 停着时看着他：这一刻请求镜头平滑转向这一点，按正常转头速度过去，不瞬转。 */
    void lookAt(TickContext context, Vec3 point);

    /** 他坐着的那辆上有给我的空座：给一个"走过去坐上"的动作；没空座、不认得这种载具为空。 */
    Optional<Action> rideAlong(TickContext context, int vehicleId, Permissions permissions);

    /** 从载具上下来：按住潜行，直到不再坐在上面。 */
    Action getOff();

    /**
     * 跟着走进传送门去另一个维度：找附近的门、走进去、等维度换过去、离开门口。
     * 只走现成的门，不建门。
     */
    Task crossPortal(String targetDimension, Permissions permissions);

    /** 帮他打：名单是离他 6 格内的敌对生物与正在打我的；玩家与中立生物不打。 */
    HelpFight helpFight(Supplier<Vec3> leader);

    /** 一场帮打：每刻推进战斗引擎一刻，读回这一刻在做什么。 */
    interface HelpFight {
        Battle.Moment tick(TickContext context);

        /** 正在撤离：打不过，引擎在跑。 */
        boolean retreating();

        Interruptibility interruptibility();

        void pause();

        void close();

        String describe();
    }
}
