// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.OptionalInt;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.behavior.navigation.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 太暗处境的生产读取：脚部与眼部实测方块光的最小值、天空光的较大值（头顶见不见得到天；
 * 亮度读数从只读亮度接缝来，测试换替身），头顶是不是只有树叶（不算树叶的地表高度不高过脚），
 * 包里有没有火把按背包视图数。角色不在世界里时如实交空，这一刻不评估。
 */
public final class LiveDarkView implements DarknessNeed.ReadsDarkness {

    private static final String TORCH_ID = "minecraft:torch";

    private final ReadsLightLevels lights;

    public LiveDarkView(ReadsLightLevels lights) {
        this.lights = lights;
    }

    @Override public DarknessNeed.DarknessFacts read(TickContext context) {
        var player = context.player();
        if (player == null || player.localPlayer() == null || player.backpack() == null) {
            return null;
        }
        BlockPos feet = player.localPlayer().blockPosition();
        BlockPos eye = BlockPos.containing(player.localPlayer().getEyePosition());
        OptionalInt atFeet = lights.blockLight(feet);
        OptionalInt atEye = lights.blockLight(eye);
        if (atFeet.isEmpty() && atEye.isEmpty()) {
            return null;
        }
        int darkest = atFeet.isEmpty() ? atEye.getAsInt()
                : atEye.isEmpty() ? atFeet.getAsInt()
                : Math.min(atFeet.getAsInt(), atEye.getAsInt());
        // 头顶见不见得到天按脚、眼里较亮的那个算：洞口、林下只要有一处透着天就不算地下。
        OptionalInt skyFeet = lights.skyLight(feet);
        OptionalInt skyEye = lights.skyLight(eye);
        OptionalInt sky = skyFeet.isEmpty() ? skyEye
                : skyEye.isEmpty() ? skyFeet
                : OptionalInt.of(Math.max(skyFeet.getAsInt(), skyEye.getAsInt()));
        // 林下：不算树叶的地表落在脚下或脚这一层，头顶就只有树叶（或什么都没有），不是洞里也不是屋里。
        // 客户端只同步了含树叶的高度图，"忽略树叶"那张一直是空的（整列读成世界最底层）；从同步的那张往下补读。
        boolean onlyLeaves = player.level() != null
                && ClientSurfaceHeight.motionBlockingNoLeaves(player.level(), feet.getX(), feet.getZ()) <= feet.getY();
        return new DarknessNeed.DarknessFacts(OptionalInt.of(darkest), carriedTorches(player.backpack()) > 0, feet, sky,
                onlyLeaves);
    }

    /** 背包里的火把数：主格为准，副手不占背包格、单独清点时另算。 */
    static int carriedTorches(BackpackView backpack) {
        int total = 0;
        for (BackpackStack stack : backpack.stacks()) {
            if (stack.itemId().equals(TORCH_ID)) total += stack.count();
        }
        return total;
    }
}
