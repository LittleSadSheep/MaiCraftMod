// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.follow.spi.RideAlong;
import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.behavior.combat.Battlefield;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.travel.DimensionTravelServices;
import org.maiwithu.maicraft.behavior.travel.DimensionTravelTask;
import org.maiwithu.maicraft.behavior.travel.SameDimensionTravel;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随身体动作的生产实现：看人走镜头的导航视角（真要瞄准点东西的动作会在同一刻盖过它），
 * 坐车先问原版的船与骆驼、再问联动登记的模组载具，过门交给出行的跨维度段，帮打交给战斗引擎。
 */
public final class LiveFollowMoves implements FollowMoves {

    /** 帮打时读多大范围的战场（格）：他身边 6 格，加上我离他的几格。 */
    private static final double HELP_FIELD = 14.0;
    /** 过门时找多远的门（格）：他刚在我眼前走进去，门就在附近。 */
    private static final int PORTAL_SEARCH = 16;
    /** 过门这一段最多花多少秒：走过去、站进门、等传送、离开门口。 */
    private static final int PORTAL_SECONDS = 60;

    private final List<RideAlong> rides;
    private final DimensionTravelServices portals;
    private final CombatSenses senses;
    private final BattleMoves battleMoves;

    /**
     * @param modRides 联动登记的模组载具同乘办法（机械动力的列车）；没装为空表
     */
    public LiveFollowMoves(WalkTo walks, Interactions interactions, List<RideAlong> modRides,
                           DimensionTravelServices portals, CombatSenses senses, BattleMoves battleMoves) {
        List<RideAlong> all = new ArrayList<>();
        all.add(new VanillaRideAlong(walks, interactions));
        all.addAll(modRides);
        this.rides = List.copyOf(all);
        this.portals = portals;
        this.senses = senses;
        this.battleMoves = battleMoves;
    }

    // 看着他：从眼睛到他眼睛算偏航与俯仰，交给导航视角——镜头按平滑转头过去；同一刻有真要瞄准的动作时让给它。
    @Override
    public void lookAt(TickContext context, Vec3 point) {
        PlayerContext player = context.player();
        LocalPlayer me = player.localPlayer();
        if (me == null || player.input() == null) return;
        Vec3 eye = me.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.2) return;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        player.input().requestNavigationLook(yaw, pitch, player.clientTick());
    }

    @Override
    public Optional<Action> rideAlong(TickContext context, int vehicleId, Permissions permissions) {
        Entity vehicle = context.player().level().getEntity(vehicleId);
        if (vehicle == null) return Optional.empty();
        for (RideAlong ride : rides) {
            Optional<Action> board = ride.board(context.player(), vehicle, permissions);
            if (board.isPresent()) return board;
        }
        return Optional.empty();
    }

    @Override
    public Action getOff() {
        return new GetsOff();
    }

    // 过门只走现成的门：许可里的改地形降到不改，找不到门就如实说没找到，不替他建一扇。
    // 跟着过门不带坐标、也不从末地出发（出口门不跟），同一个维度里的那段用只走路的那一份。
    @Override
    public Task crossPortal(String targetDimension, Permissions permissions) {
        Permissions walkOnly = new Permissions(Permissions.BlockChanges.NONE, permissions.fight(),
                permissions.useRareItems(), permissions.killAnimals(), permissions.survivalNeeds(),
                permissions.protectedLandmarks());
        return new DimensionTravelTask(new DimensionTravelTask.Journey(targetDimension, null, PORTAL_SEARCH,
                PORTAL_SECONDS, walkOnly), portals, SameDimensionTravel.walkingOnly(portals));
    }

    // 帮打的名单：他身边 6 格内的敌对生物，加上正在打我的；玩家不打（打不打人由 LLM 定）。
    @Override
    public HelpFight helpFight(Supplier<Vec3> leader) {
        Battle battle = new Battle(senses, battleMoves, new Battlefield.Roster() {
            @Override public boolean wanted(CombatSenses.Threat threat, boolean attacker, boolean chasing) {
                if (threat.kind() == ThreatAssessment.Kind.PLAYER) return false;
                Vec3 him = leader.get();
                boolean nearHim = him != null
                        && Math.hypot(threat.x() - him.x, threat.z() - him.z) <= LiveFollowView.FIGHT_RADIUS
                        && Math.abs(threat.y() - him.y) <= 4;
                return attacker || nearHim;
            }

            @Override public boolean primary(CombatSenses.Threat threat) {
                Vec3 him = leader.get();
                return him != null
                        && Math.hypot(threat.x() - him.x, threat.z() - him.z) <= LiveFollowView.FIGHT_RADIUS;
            }
        }, HELP_FIELD);
        return new HelpFight() {
            @Override public Battle.Moment tick(TickContext context) {
                return battle.tick(context);
            }

            @Override public boolean retreating() {
                return battle.retreating();
            }

            @Override public Interruptibility interruptibility() {
                return battle.interruptibility();
            }

            @Override public void pause() {
                battle.pause();
            }

            @Override public void close() {
                battle.close();
            }

            @Override public String describe() {
                return battle.describe();
            }
        };
    }
}
