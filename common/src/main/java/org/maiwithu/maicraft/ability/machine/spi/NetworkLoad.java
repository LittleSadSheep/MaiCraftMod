// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;

/**
 * 一格机器方块在它那种网上吃多少、给多少：应力网按每转的应力（转速 n 时都乘 n），ME 网按频道。
 * 审阅把整份蓝图的加起来估一下够不够用，只作估计，不拦施工。
 *
 * @param network 哪种网，取值同网络读取器的种类（kinetic、me……）
 * @param draw    吃多少；不吃为 0
 * @param supply  给多少；不给为 0
 * @param unit    单位的一句话，例如"每转应力"、"频道"
 */
public record NetworkLoad(String network, double draw, double supply, String unit) {

    public NetworkLoad {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(unit, "unit");
        if (draw < 0 || supply < 0) throw new IllegalArgumentException("吃和给都不能是负数：" + draw + " / " + supply);
    }
}
