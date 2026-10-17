// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import java.util.List;

import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 随行补光开关与状态的结果细节：开关、阈值、身上与副手的火把数、最近实测仍暗的路线格
 * （只报走过并采样过的格，卸载的不猜）。
 */
public record FollowLightStatus(boolean enabled, int minimumLight, int torchesCarried, int torchesOffhand,
                                List<DarkCell> darkOnRoute) implements ResultDetails {

    /** 一格仍暗的路线格：坐标、当时的方块光、什么时候走过的。 */
    public record DarkCell(String pos, int light, long tick) {}

    /** 从会话设置与火把清点照实报一份状态。 */
    public static FollowLightStatus of(FollowLighting lighting, int torchesCarried, int torchesOffhand) {
        return new FollowLightStatus(lighting.enabled(), lighting.minimumLight(),
                torchesCarried, torchesOffhand,
                lighting.darkOnRoute().stream()
                        .map(cell -> new DarkCell(cell.pos().toShortString(), cell.light(), cell.tick()))
                        .toList());
    }
}
