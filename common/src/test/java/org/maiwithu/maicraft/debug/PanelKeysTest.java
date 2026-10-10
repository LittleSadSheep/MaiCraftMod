// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F9 组合键：单按松开才切档，按住 F9 再按字母只算组合键。 */
class PanelKeysTest {

    @Test
    void f9AloneSwitchesLevelWhenReleased() {
        PanelKeys keys = new PanelKeys();

        assertEquals(List.of(), keys.poll(true, false, false, false), "按下时不切");
        assertEquals(List.of(), keys.poll(true, false, false, false), "按着不切");
        assertEquals(List.of(PanelKeys.Press.NEXT_LEVEL), keys.poll(false, false, false, false));
    }

    @Test
    void chordDoesNotAlsoSwitchLevelOnRelease() {
        // 按住 F9 再按 H：切页一次，松开 F9 时不能顺带把面板切走。
        PanelKeys keys = new PanelKeys();
        keys.poll(true, false, false, false);

        assertEquals(List.of(PanelKeys.Press.SWITCH_PAGE), keys.poll(true, true, false, false));
        assertEquals(List.of(), keys.poll(true, true, false, false), "一直按着 H 不会反复切");
        assertEquals(List.of(PanelKeys.Press.TOGGLE_PATH_LINES), keys.poll(true, false, true, false));
        assertEquals(List.of(PanelKeys.Press.TOGGLE_BLUEPRINT), keys.poll(true, false, false, true), "F9+B 开关施工预览");
        assertEquals(List.of(), keys.poll(false, false, false, false));
    }

    @Test
    void lettersWithoutF9DoNothing() {
        PanelKeys keys = new PanelKeys();

        assertEquals(List.of(), keys.poll(false, true, true, true));
    }
}
