// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

import java.util.List;

import org.maiwithu.maicraft.kernel.param.ParamSpec;

/**
 * 出行方式：把角色往目的地送一段的一种办法（Create 电梯、喷气背包、火车、自己的载具）。
 * 行程规划先向每种方式问一份报价（只读，可以分几刻答完），比耗时挑一份，再让那种方式跑这一段；
 * 跑完从当前位置接着规划。步行由行程自己做，不在这里登记。
 */
public interface TravelMode {

    /** 这种方式的取值与一句话说明：自报进 travel 的 via 参数表，装了才出现。 */
    TravelVia via();

    /**
     * 只读：从当前现场能把角色往目的地送一段吗。答得出就 {@link OfferSink#offer}，答不出就
     * {@link OfferSink#decline} 写一句原因；这一刻还在看现场（楼层表没同步、轨道图没读完）就什么都不答，
     * 规划下一刻再问。请求里的目的地为 null 时是只用这种方式、不往别处去（只坐电梯换楼层）。
     */
    void offer(TravelRequest request, OfferSink sink);

    /**
     * 这种方式自己的 travel 参数（电梯的 floor、载具的 vehicle），装了才出现在参数表里；
     * 给了这些参数就是用这种方式，没给 via 时按它算。名字要先登记进参数名表。
     */
    default List<ParamSpec> options() {
        return List.of();
    }

    /**
     * 跑报价里的这一段：走到呼梯点、等、上、坐、下……站稳在这段终点算做完。
     *
     * @param request 出这份报价时的请求（这次的许可、给方式的话）
     */
    TravelLeg start(TravelRequest request, TravelOffer offer);
}
