// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/** 目标模型的种类，能力描述符用它声明自己接受哪些种类（docs/design/07 第 4 节）。 */
public enum TargetKind {
    HERE,
    SEEN,
    LANDMARK,
    POSITION,
    PLAYER,
    DIRECTION,
    PREVIOUS
}
