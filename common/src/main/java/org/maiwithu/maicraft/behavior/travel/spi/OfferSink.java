// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

/** 收一种方式的回答：答得出给报价，答不出写一句原因；装置不在、模组没装是"不支持"，有装置但送不到是"送不到"。 */
public interface OfferSink {

    /** 能送这一段。 */
    void offer(TravelOffer offer);

    /**
     * 送不了。
     *
     * @param reason      一句原因，例如"附近没有通到那一层的电梯"
     * @param unsupported 是"这里没有这种方式"（装置不在、没装模组）而不是"有但送不到"
     */
    void decline(String reason, boolean unsupported);
}
