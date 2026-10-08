// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/** 目标对象的种类；能力规格用它声明自己接受哪些种类。 */
public enum TargetKind {
    HERE,
    SEEN,
    LANDMARK,
    POSITION,
    PLAYER,
    DIRECTION,
    PREVIOUS
}
