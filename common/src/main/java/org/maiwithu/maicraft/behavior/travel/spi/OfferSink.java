// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 收一种方式的回答：答得出给报价，答不出写一句原因和是哪一种送不了——
 * 这里没有这种方式（装置不在、模组没装）是 UNSUPPORTED，有但送不到是 UNREACHABLE，
 * 给这种方式的话说错了（楼层名对不上）是 INVALID_PARAMETER。
 */
public interface OfferSink {

    /** 能送这一段；一次可以给几份（几台电梯、几趟车）。 */
    void offer(TravelOffer offer);

    /**
     * 送不了。
     *
     * @param kind   哪一种送不了：UNSUPPORTED、UNREACHABLE 或 INVALID_PARAMETER
     * @param reason 一句原因，例如"附近没有通到那一层的电梯"；话说错了时带上该怎么说（实际的楼层表）
     */
    void decline(Problem.Kind kind, String reason);
}
