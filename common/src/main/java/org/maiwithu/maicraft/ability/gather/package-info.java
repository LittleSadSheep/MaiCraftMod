// SPDX-License-Identifier: GPL-3.0-only
/**
 * 采集（maicraft:gather）：对世界里的一个目标做一次采集动作——收一株熟了的庄稼、挖一格方块、
 * 走近捡起地上的掉落物。单点动作，薄能力：不接来源排序，也不递归备料；要"弄到 N 个"用拿东西。
 *
 * <p>本包只有 {@code GatherAbility} 对外公开；注册进能力清单由启动装配完成。
 */
package org.maiwithu.maicraft.ability.gather;
