// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待在头顶遮住的暗处（坑道、矿洞、没点灯的屋里）、身上又没有火把：自动补光插不了，暗处会刷怪，观众也看不清。
 * 提醒 LLM 先弄些火把；只给常识建议，不自己去弄、不接管角色。亮起来了、见得到天了、身上有火把了就撤。
 * 实机 10-13 长程挖矿一路摸黑挖到 Y=38，包里一支火把都没有，自动补光一支没插，谁也不知道。
 */
final class DarkWithoutTorchReminder {

    static final String ID = "dark_without_torches";
    /** 暗到这个亮度以下算黑（刷怪的亮度）。 */
    private static final int DARK_BELOW = 8;
    /** 天空光到这么多算露天：露天的方块光本来就是 0，不算暗处。 */
    private static final int OPEN_SKY = 8;
    /** 连着暗这么久（刻）才提醒：走过一段暗处不催。 */
    private static final long DARK_TICKS = 10 * 20;
    private static final long MAX_OBSERVATION_GAP = 5 * 20;

    /** 一刻的观察：脚下的方块光、天空光（读不到为 -1）、身上（主背包和副手）有几支火把。 */
    record Observation(long tick, int blockLight, int skyLight, int torches) {}

    private final ReminderBoard board;
    private long previousTick = -1;
    private long darkSince = -1;

    DarkWithoutTorchReminder(ReminderBoard board) {
        this.board = board;
    }

    void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            darkSince = -1;
            board.remove(ID, "亮度的观察中断了", now.tick());
        }
        previousTick = now.tick();
        boolean unknown = now.blockLight() < 0 || now.skyLight() < 0;
        boolean dark = !unknown && now.blockLight() < DARK_BELOW && now.skyLight() < OPEN_SKY;
        if (!dark || now.torches() > 0) {
            darkSince = -1;
            board.remove(ID, now.torches() > 0 ? "身上有火把了" : "不暗了", now.tick());
            return;
        }
        if (darkSince < 0) darkSince = now.tick();
        if (now.tick() - darkSince < DARK_TICKS) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("block_light", now.blockLight());
        evidence.put("sky_light", now.skyLight());
        evidence.put("torches_carried", 0);
        evidence.put("dark_observed_ticks", now.tick() - darkSince);
        board.update(ID, "这一带很暗（方块光 " + now.blockLight() + "），身上没有火把：自动补光插不了，暗处会刷怪，观众也看不清。"
                        + "可以先弄些火把再接着干（煤或木炭加木棍，背包里就能合成）。",
                evidence, List.of("弄不弄、弄多少由手上的事决定；提醒不会自己去拿或合成。"), now.tick());
    }

    void clear() {
        previousTick = darkSince = -1;
    }
}
