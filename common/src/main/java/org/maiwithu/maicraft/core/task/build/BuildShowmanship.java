package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.entity.InputDriver;

/**
 * 旧施工表现辅助类，目前没有生产调用者。保留了朝工作区域看过去的计算，庆祝方法为空。
 */
final class BuildShowmanship {
    private final LocalPlayer player;
    private boolean crouching;

    BuildShowmanship(LocalPlayer player, BuildInventory ignored) { this.player = player; }
    boolean crouching() { return crouching; }

    // 看向本批坐标中心的平均位置；低处工作时只记录 crouching 标志，这里没有发送蹲下按键。
    void performWork(List<BlockPos> touched, BlockState sample) {
        if (touched.isEmpty()) return;
        Vec3 centre = Vec3.ZERO;
        for (BlockPos pos : touched) centre = centre.add(Vec3.atCenterOf(pos));
        centre = centre.scale(1.0 / touched.size());
        InputDriver.lookAt(player, centre);
        crouching = centre.y < player.getY() + 0.6;
    }

    void celebrate(BlockPos siteMin, BlockPos siteMax) {
        // 未实现额外庆祝动作，调用它不会播放粒子或声音。
    }
}
