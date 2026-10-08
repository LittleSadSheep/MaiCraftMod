// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

/** 读取当前世界的方块操作编号，以及服务器确认后已完成本地校正的编号；换世界后重新记。 */
public interface BlockUseAcknowledgement {
    int maicraft$currentBlockSequence();
    int maicraft$acknowledgedBlockSequence();
}
