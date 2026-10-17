// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 组装的结果细节。
 *
 * @param structure   组装出来（或拆回前）的结构身份；没有为空串
 * @param alreadyDone 开始时就是要的样子，什么都没做
 * @param converted   拉了之后真的转换了
 * @param blocks      组装进结构的方块数；拆回或不知道为 -1
 * @param note        经过一句话
 */
record VehicleAssembleDetails(String structure, boolean alreadyDone, boolean converted, int blocks, String note)
        implements ResultDetails {}
