package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.entity.InputDriver;

/** Client-safe presentation: leased camera/sneak intent only; native placement supplies its own swing. */
final class BuildShowmanship {
    private final LocalPlayer player;
    private boolean crouching;

    BuildShowmanship(LocalPlayer player, BuildInventory ignored) { this.player = player; }
    boolean crouching() { return crouching; }

    void performWork(List<BlockPos> touched, BlockState sample) {
        if (touched.isEmpty()) return;
        Vec3 centre = Vec3.ZERO;
        for (BlockPos pos : touched) centre = centre.add(Vec3.atCenterOf(pos));
        centre = centre.scale(1.0 / touched.size());
        InputDriver.lookAt(player, centre);
        crouching = centre.y < player.getY() + 0.6;
    }

    void celebrate(BlockPos siteMin, BlockPos siteMax) {
        // No server particle/sound broadcast is available to a LocalPlayer task. The confirmed
        // native block placements are the presentation and remain fully mod-compatible.
    }
}
