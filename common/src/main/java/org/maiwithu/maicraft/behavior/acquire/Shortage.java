// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一样东西没拿到的经过：要什么、还缺几个、为了什么，这一层问过、试过各来源的结果，以及备料时下一层没拿到的。
 *
 * <p>备料一层套一层：床要羊毛，羊毛要线。每一层只交代自己问过、试过什么；下一层没拿到的经过收在 {@code causes} 里，
 * 由最外层讲给 LLM 时摊平讲一次——同一样东西只讲一次，原因一字不差的几样合成一组说。
 * 不把下一层的整段说明套进上一层：十几种做法、几层备料乘起来，一次失败会写成几万字，还把同一句话重复几十遍。
 *
 * @param key     要的东西的写法（物品 ID 或 # 开头的标签），认同一样东西用
 * @param wanted  要的东西，给人看的写法
 * @param missing 还缺几个
 * @param purpose 用途标签：为谁备的
 * @param reasons 这一层问过、试过的结果，一条一句
 * @param causes  这一层备料时下一层没拿到的经过
 */
record Shortage(String key, String wanted, int missing, String purpose, List<String> reasons, List<Shortage> causes) {

    Shortage {
        reasons = List.copyOf(reasons);
        causes = List.copyOf(causes);
    }

    /**
     * 备料时没拿到的那一段："备料时没拿到的有 N 样：……"；没有下一层的经过时为空串。
     * 摊平按先后：同一样东西、同样的原因只讲第一次（换一种做法又要到它时，这一次不会再去问，经过也是同一份）；
     * 原因里把东西名换成"它"以后一字不差的几样合成一组（十一种颜色的羊毛都卡在没有白色羊毛上），只讲一遍，列全名字与件数。
     */
    static String explain(List<Shortage> causes) {
        Map<String, Shortage> distinct = new LinkedHashMap<>();
        collect(causes, distinct);
        if (distinct.isEmpty()) return "";
        Map<String, List<Shortage>> groups = new LinkedHashMap<>();
        for (Shortage shortage : distinct.values()) {
            groups.computeIfAbsent(shortage.pattern(), pattern -> new ArrayList<>()).add(shortage);
        }
        StringBuilder text = new StringBuilder("备料时没拿到的有 ").append(distinct.size()).append(" 样：");
        boolean first = true;
        for (Map.Entry<String, List<Shortage>> group : groups.entrySet()) {
            if (!first) text.append("。");
            first = false;
            List<Shortage> members = group.getValue();
            if (members.size() == 1) {
                Shortage only = members.getFirst();
                text.append(only.wanted()).append("×").append(only.missing())
                        .append("（").append(only.purpose()).append("）：").append(String.join("；", only.reasons()));
            } else {
                text.append(String.join("、", members.stream().map(member -> member.wanted() + "×" + member.missing()).toList()))
                        .append("这 ").append(members.size()).append(" 样都是：").append(group.getKey());
            }
        }
        return text.toString();
    }

    // 先序摊平：上一层的东西排在它备料要的东西前面，读起来是从要的东西往下追到卡住的那一样。
    private static void collect(List<Shortage> causes, Map<String, Shortage> distinct) {
        for (Shortage cause : causes) {
            distinct.putIfAbsent(cause.key() + "\n" + cause.pattern(), cause);
            collect(cause.causes(), distinct);
        }
    }

    // 原因的样子：东西名换成"它"，同样卡在一处的几样东西认得出是一回事。
    private String pattern() {
        return String.join("；", reasons).replace(wanted, "它");
    }
}
