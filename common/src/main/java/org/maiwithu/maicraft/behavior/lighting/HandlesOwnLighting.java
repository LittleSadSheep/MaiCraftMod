// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

/**
 * 自己管照明的任务的特征接口：照明的区域补光任务声明它，太暗这项生存需求看到就让位，
 * 不往验收范围里插野火把。其余施工不声明——盖房途中顺手插一支无害。
 *
 * <p>内核与生存需求都不认识任何具体能力，只认这个接口。
 */
public interface HandlesOwnLighting {
}
