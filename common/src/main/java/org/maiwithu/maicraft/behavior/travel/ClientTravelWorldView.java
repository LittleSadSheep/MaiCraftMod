// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行现场的读端：从当刻的角色上下文读角色所在位置与水平朝向。
 *
 * <p>位置带维度；朝向只给东南西北之一，相对的前后左右由目的地解析自己换算。
 * 没有角色上下文时位置给 null，解析方以"不知道自己在哪"处理，不猜。
 */
public final class ClientTravelWorldView implements TravelWorldView {

    private final Supplier<PlayerContext> context;

    public ClientTravelWorldView(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public WorldPosition currentSpot() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return null;
        }
        LocalPlayer player = current.localPlayer();
        return new WorldPosition((int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), player.level().dimension().location().toString());
    }

    @Override
    public Target.Toward currentFacing() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return null;
        }
        return towardOf(current.localPlayer().getYRot());
    }

    /**
     * 视角角换成世界方位：0 朝南，顺时针增大，90 朝西。朝向只给东南西北之一，
     * 斜向按东西、南北哪个分量大靠到哪边；与观察视图共用同一套方位换算
     * （{@code DirectionWords}），出行说的方向和感知报的方向是同一套。纯函数，离线测试直接喂角度。
     */
    static Target.Toward towardOf(float facingYawDegrees) {
        // 前方向向量与方位词的换算共用感知的规则：dx = -sin(yaw)，dz = cos(yaw)。
        double dx = -Math.sin(Math.toRadians(facingYawDegrees));
        double dz = Math.cos(Math.toRadians(facingYawDegrees));
        // 东西分量大朝东西，南北分量大朝南北：45 度的斜向不会丢给默认值。
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Target.Toward.EAST : Target.Toward.WEST;
        }
        return dz > 0 ? Target.Toward.SOUTH : Target.Toward.NORTH;
    }
}
