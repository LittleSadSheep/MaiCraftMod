// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 审阅机器蓝图的结果细节：发现的问题、材料账、动力与频道的估计。
 * 没有通过与否的结论：报了问题也照建，审阅只是把看不出来的事算出来。
 *
 * @param issues   发现：位置、问题、建议
 * @param materials 材料：要几件、身上有几件、缺几件
 * @param power    动力估计的一句话；读不到数值时写估不出的原因
 * @param channels 频道估计的一句话
 */
record MachineReviewDetails(List<MachineReview.Issue> issues, List<MachineReview.Material> materials,
                            String power, String channels) implements ResultDetails {

    MachineReviewDetails {
        issues = List.copyOf(issues);
        materials = List.copyOf(materials);
    }
}
