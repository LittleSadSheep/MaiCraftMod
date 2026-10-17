// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * machine_run 交给机器类型从头做到尾的一次：蓝图桌写蓝图、蓝图加农炮照蓝图施工这类不按配方投料的机器。
 * 一整段动作；做完交回做出了几件、做全了没有、这次值得知道的事。动作失败带问题收场，做了一部分的照实算数。
 */
public interface FullRun extends Action {

    /** 做出了几件：写出的蓝图一张、加农炮放下的方块数。 */
    int produced();

    /** 要的都做完了没有：蓝图写出来了、加农炮报完工了。 */
    boolean complete();

    /** 这次值得知道的事：上传了多少、加农炮停在什么状态、缺什么料。 */
    List<String> notes();
}
