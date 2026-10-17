// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.explore.ClientExplorationWorld;
import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.behavior.navigation.util.ClientSurfaceHeight;
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
    public boolean standable(int x, int y, int z) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return true;
        BlockPos feet = new BlockPos(x, y, z);
        if (!current.level().isLoaded(feet)) return true;
        return BlockPassability.isStandable(current.level(), feet);
    }

    @Override
    public OptionalInt surfaceFeet(int x, int z) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return OptionalInt.empty();
        BlockPos column = new BlockPos(x, 0, z);
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        if (!current.level().isLoaded(column)) return OptionalInt.empty();
        // 地表最高的实心（不算树叶）方块上面那一格就是站的地方。
        // 客户端只同步了含树叶的高度图，"忽略树叶"那张一直是空的（整列读成世界最底层）；从同步的那张往下补读。
        return OptionalInt.of(ClientSurfaceHeight.motionBlockingNoLeaves(current.level(), x, z));
    }

    // 顶盖样本和探索逐列抽样读的是同一份：回没回到露天、哪儿是露天落脚处，两边按同一条事实判。
    @Override
    public ColumnSample column(int x, int z) {
        PlayerContext current = context.get();
        return ClientExplorationWorld.sample(current == null ? null : current.level(), x, z);
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
