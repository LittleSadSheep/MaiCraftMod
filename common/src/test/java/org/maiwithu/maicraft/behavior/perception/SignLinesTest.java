// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 告示牌上的字：正面连成一句，以及按名字认牌子时"写着这个名字"怎么算。 */
class SignLinesTest {

    private static SignLines front(String... lines) {
        return new SignLines(List.of(lines), List.of("", "", "", ""));
    }

    @Test
    void frontSentenceJoinsNonEmptyLines() {
        assertEquals("仓库 往前 20 格", front("仓库", "", " 往前 20 格 ", "").frontSentence());
    }

    @Test
    void readsANameOnOneLineEvenWithDecorations() {
        // 主播爱给路牌加括号、箭头：只比字母数字汉字，不分大小写。
        assertTrue(front("仓库", "", "", "").reads("仓库"));
        assertTrue(front("【仓库】").reads("仓库"));
        assertTrue(front("→ 仓库").reads(" 仓库 "));
        assertTrue(front("Main Base").reads("main base"));
        assertTrue(front("仓库", "往前 20 格").reads("仓库"), "下面一行写着路怎么走，第一行照样认得出");
    }

    @Test
    void readsANameSplitAcrossLines() {
        // 牌子一行写不下，名字拆成两行写。
        assertTrue(front("矿洞", "入口", "", "").reads("矿洞入口"));
        assertTrue(front("仓", "库").reads("仓库"));
    }

    @Test
    void containingTheNameIsNotReadingIt() {
        // 比"相同"不比"包含"：张三的仓库不是"仓库"，一行写完的"仓库往前20格"也不是。
        assertFalse(front("张三的仓库").reads("仓库"));
        assertFalse(front("仓库往前20格").reads("仓库"));
        assertFalse(front("仓库", "往前 20 格").reads("往前"));
    }

    @Test
    void backSideCountsOnItsOwn() {
        SignLines sign = new SignLines(List.of("欢迎", "", "", ""), List.of("", "后门", "", ""));
        assertTrue(sign.reads("后门"));
        assertFalse(sign.reads("欢迎后门"), "正反两面不连起来比");
    }

    @Test
    void emptyOrSymbolOnlyNamesReadNothing() {
        assertFalse(front("", "", "", "").reads(""));
        assertFalse(front("→", "", "", "").reads("→"));
        assertFalse(front("仓库").reads(null));
    }
}
