// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

/**
 * 沿路走的任务的特征接口：出行、探索这类"走路本身就是正事"的任务声明它，
 * 太暗这项生存需求在它走路途中也插得进来（落地站稳、停下安全时），顺手插一支火把接着走，
 * 不用等走完整趟——等走完再插，就成了摸黑走完全程。
 *
 * <p>内核与生存需求都不认识任何具体能力，只认这个接口。
 */
public interface TorchesAlongTheWay {
}
