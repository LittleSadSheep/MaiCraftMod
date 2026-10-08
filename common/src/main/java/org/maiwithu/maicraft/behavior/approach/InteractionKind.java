// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

/**
 * 交互种类：要对目标做的是哪一类事，决定"够得着"按哪条规则判。
 * 方块与实体各按角色身上的同名交互距离属性；床的服务端距离比属性短得多，单独一档。
 */
public enum InteractionKind {
    /** 对一个方块右键或挖掘，例如开箱子、点工作台。 */
    BLOCK,
    /** 攻击或右键一个实体，例如挤奶、打怪。 */
    ENTITY,
    /** 上床睡觉：服务端按床底面中心单独卡距离，不读交互距离属性。 */
    BED
}
