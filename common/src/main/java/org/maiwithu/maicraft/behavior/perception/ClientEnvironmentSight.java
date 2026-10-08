// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.WorldTime;

/**
 * 环境观察的读端：读角色所在位置的时间、天气、光照与生物群系。
 *
 * <p>时间按世界时间规则给出相位与离天亮多久；光照取角色所在格的合成亮度；
 * 生物群系给注册 ID。没有角色上下文时返回 null，调用方这一刻跳过环境观察。
 */
public final class ClientEnvironmentSight implements EnvironmentSight {

    private final Supplier<PlayerContext> context;

    public ClientEnvironmentSight(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public SceneEnvironment current() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return null;
        }
        LocalPlayer player = current.localPlayer();
        return new SceneEnvironment(
                timeText(current.level().getDayTime()),
                weatherWord(current.level().getRainLevel(1.0F), current.level().getThunderLevel(1.0F)),
                current.level().getMaxLocalRawBrightness(player.blockPosition()),
                biomeId(player));
    }

    /**
     * 时间的一句话说法：夜里才报离天亮多久——白天报"距天亮"没有意义。
     * 天亮按世界时间规则的凌晨起点算；纯函数，离线测试直接喂时刻。
     */
    static String timeText(long dayTime) {
        long timeOfDay = WorldTime.timeOfDayOf(dayTime);
        return switch (WorldTime.phase(dayTime)) {
            case DAWN -> "凌晨";
            case DAY -> "白天";
            case DUSK -> "傍晚";
            case NIGHT -> {
                // 凌晨从 23000 刻开始；一分钟一千二百刻，向上取整免得报"还有 0 分钟"。
                long minutes = Math.max(1, Math.round((23_000L - timeOfDay) / 1_200.0));
                yield "夜晚，距天亮约 " + minutes + " 分钟";
            }
        };
    }

    // 天气词：雷暴比雨大，先判雷暴；阈值取一半，雨量阈值以下的毛毛雨不折腾观察。纯函数。
    static String weatherWord(float rainLevel, float thunderLevel) {
        if (thunderLevel >= 0.5F) {
            return "雷暴";
        }
        if (rainLevel >= 0.5F) {
            return "雨";
        }
        return "晴";
    }

    private static String biomeId(LocalPlayer player) {
        return player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("未知");
    }
}
