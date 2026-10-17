// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 用机器做东西的结果细节：用的哪条配方、投了什么、出口新增多少、拿回多少，结束时机器什么状态。
 *
 * <p>机器内部还留着什么原料这里读不到（不透视机器内部）：要看就 use 打开界面。
 *
 * @param recipe    用的配方：类别与注册 ID；没给 item 只开机时为 null
 * @param batches   按要做的件数算出要做几批
 * @param fed       投进去的东西与数量
 * @param produced  出口里这次新增几件目标物品
 * @param collected 拿进背包几件；collect 为 false 时为 0，东西留在出口
 * @param machine   结束时机器的运行状态一句话，例如"在转""过载""转速 0"
 * @param notes     过程中值得知道的事，例如配方要的流体带不进机器
 */
record MachineRunDetails(Recipe recipe, int batches, List<Fed> fed, int produced, int collected,
                         String machine, List<String> notes) implements ResultDetails {

    MachineRunDetails {
        fed = List.copyOf(fed);
        notes = List.copyOf(notes);
        machine = machine == null ? "" : machine;
    }

    /** 用的配方：配方类别（例如 create:mixing）与注册 ID，都给 LLM 对着 lookup 用。 */
    record Recipe(String category, String recipeId) {
    }

    /** 投进机器的一样东西。 */
    record Fed(String item, int count) {
    }
}
