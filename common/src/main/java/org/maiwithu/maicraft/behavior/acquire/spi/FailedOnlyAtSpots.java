// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

/**
 * 来源动作失败时说清坏在哪：只坏在现场的几处（这棵树顶上那根够不着、这块矿挖不下来），
 * 来源已经把那几处记下、一阵子不再先挑，换别的格、别的树、别的矿还能接着拿。
 * 引擎据此只记一笔经过，不把整个来源划掉；连着几次都这样一件没拿到，才当这个来源这次走不通。
 * 来源的动作不实现它时，失败按整个来源走不通算。
 */
public interface FailedOnlyAtSpots {

    /** 这次失败是不是只坏在已经记下的那几处：是的话来源换别处还能接着拿。 */
    boolean onlyThoseSpotsFailed();
}
