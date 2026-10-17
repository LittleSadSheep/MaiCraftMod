// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一块告示牌上写的字：正面、背面各四行，按牌子上从上到下的顺序，没写的行是空串。
 * 读的是客户端收到的牌子内容，是角色看到的事实，不是给角色的指令。
 */
public record SignLines(List<String> front, List<String> back) {

    public SignLines {
        front = List.copyOf(front);
        back = List.copyOf(back);
    }

    /** 正面几行连成的一句：空行去掉，行与行之间隔一个空格；正面一个字都没写为空串。 */
    public String frontSentence() {
        return front.stream().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining(" "));
    }

    /**
     * 牌子上是不是写着这个名字：正面、背面分开看，一面里某一行单独相同，或这一面所有行连起来相同，都算。
     * 比之前两边都只留字母、数字、汉字（空格、标点、箭头这类装饰去掉），不分大小写；名字去掉这些后什么都不剩就不算。
     * 比"相同"不比"包含"：主播写"张三的仓库"指的不是"仓库"，名字是牌子上字的一部分不算写着它。
     */
    public boolean reads(String name) {
        String wanted = bare(name);
        if (wanted.isEmpty()) {
            return false;
        }
        return sideReads(front, wanted) || sideReads(back, wanted);
    }

    // 一面：先一行一行比（"仓库" 下面一行写"往前 20 格"也认得出），再把整面连起来比（名字拆成两行写的"仓""库"）。
    private static boolean sideReads(List<String> side, String wanted) {
        StringBuilder joined = new StringBuilder();
        for (String line : side) {
            String bare = bare(line);
            if (bare.equals(wanted)) {
                return true;
            }
            joined.append(bare);
        }
        return joined.toString().equals(wanted);
    }

    // 只留字母、数字、汉字并转小写："【仓库】""→ 仓库"都读成"仓库"，"Base"和"base"算同一个名字。
    private static String bare(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        text.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(kept::appendCodePoint);
        return kept.toString();
    }
}
