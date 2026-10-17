// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 施工收场时交给使用方的一步：按这次施工落下的现场记账（盖完记一份建筑档案、改建后对整栋比一遍），
 * 再给出最后交出去的结果细节。施工做完、做了一部分、卡住、被取消都只问一次；没有这一步就照施工自己的细节。
 *
 * <p>记账存不进去只是结果里多一句，不改判施工；施工引擎不认识档案，档案是能力自己的事。
 */
public interface RecordsConstruction {

    /** 不记账：照施工自己的细节交出去。 */
    RecordsConstruction NONE = (details, world) -> details;

    /**
     * @param details 施工自己的结果细节（每格结局、问题、用料、临时方块去向）
     * @param world   收场这一刻的现场
     * @return 最后交出去的结果细节
     */
    ResultDetails record(ConstructionDetails details, ReadsBlocks world);
}
