// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.baritone.BreathingRoute;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 水面被盖住时游去能露头的地方的生产实现：按身体真正游得过去的通道在十六格内找空气（每一处拐弯都保留，
 * 不把透气口当成能穿墙直上），按游泳时间算要多少氧气；跟着路线游时镜头朝下一个点、往前游，要上就按跳、要下就按潜行。
 */
public final class LiveAirRoutes implements BreathTask.SwimsToAir {

    /** 一次找路最多展开多少格：和寻路共用的通道搜索自己也有上限。 */
    private static final int SEARCH_BUDGET = 4096;
    /** 离路线上的下一个点这么近就算到了，接着往下一个点游。 */
    private static final double REACHED = 0.45;

    @Override
    public Optional<BreathTask.AirRoute> route(TickContext context) {
        LocalPlayer player = context.player() == null ? null : context.player().localPlayer();
        if (player == null) return Optional.empty();
        var view = BreathingRoute.observed(player, new LongOpenHashSet());
        var search = new BreathingRoute.Search(player.position());
        // 搜索每次推进有一毫秒的时间上限：在这一刻里推到出结果或搜完为止。
        for (int round = 0; round < 64 && !search.done(); round++) search.advance(view, SEARCH_BUDGET);
        BreathingRoute.Route found = search.result();
        if (found == null || !found.reachesAir()) return Optional.empty();
        return Optional.of(new BreathTask.AirRoute(found.points(), found.requiredAir(1.0)));
    }

    @Override
    public boolean swimToward(TickContext context, Vec3 point) {
        LocalPlayer player = context.player().localPlayer();
        Vec3 here = player.position();
        Vec3 delta = point.subtract(here);
        if (delta.length() <= REACHED) return true;
        double flat = Math.hypot(delta.x, delta.z);
        float yaw = flat < 1.0E-3 ? player.getYRot() : (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.max(flat, 1.0E-3)));
        long tick = context.player().clientTick();
        PlayerInput input = context.player().input();
        input.requestLook(yaw, pitch, tick);
        // 水平方向靠朝向往前游；竖直方向要上按跳、要下按潜行，差得不多就都不按。
        boolean up = delta.y > 0.2;
        boolean down = delta.y < -0.2;
        input.applyMovement(new PlayerInput.Movement(flat > 0.15 ? 1.0f : 0.0f, 0.0f, up, down, false), tick);
        return false;
    }
}
