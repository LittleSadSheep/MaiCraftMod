// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * F9 组合键的判断：每刻给出 F9、H、N 此刻按没按着，回答这一刻要做什么。
 *
 * <p>单按 F9 在松开时才切档：按住 F9 再按字母是组合键，松开 F9 时不能顺带把面板切走。
 * 字母只在刚按下的那一刻算一次，一直按着不会每刻反复切换。
 */
final class PanelKeys {
    /** 这一刻要做的事。 */
    enum Press {
        /** 切到下一档。 */
        NEXT_LEVEL,
        /** 在"此刻"和"最近的目标"之间切换。 */
        SWITCH_PAGE,
        /** 开关导航路线。 */
        TOGGLE_PATH_LINES
    }

    private boolean f9WasDown;
    private boolean hWasDown;
    private boolean nWasDown;
    /** 这次按住 F9 期间按过组合键。 */
    private boolean chordUsed;

    /** 每刻调用一次。 */
    List<Press> poll(boolean f9, boolean h, boolean n) {
        List<Press> presses = new ArrayList<>(1);
        if (f9 && h && !hWasDown) {
            chordUsed = true;
            presses.add(Press.SWITCH_PAGE);
        }
        if (f9 && n && !nWasDown) {
            chordUsed = true;
            presses.add(Press.TOGGLE_PATH_LINES);
        }
        if (!f9 && f9WasDown) {
            if (!chordUsed) presses.add(Press.NEXT_LEVEL);
            chordUsed = false;
        }
        f9WasDown = f9;
        hWasDown = h;
        nWasDown = n;
        return presses;
    }
}
