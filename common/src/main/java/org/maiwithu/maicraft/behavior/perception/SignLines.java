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
}
