// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

/**
 * 子任务携带已验证位置的内部来源，不序列化到 TaskResult。
 * 后续 prior_result 可据此复用真实地点，而不要求模型传递具体坐标。
 */
public interface InternalPositionReceipt {
    record Position(int x, int y, int z, String dimension) {}

    Position internalVerifiedPosition();
}
