// SPDX-License-Identifier: GPL-3.0-only
/**
 * 感知：让 LLM 看到玩家看到的——周围场景、实体、地形特征、设施、声音，用统一的方位说法，并给每样东西一个观察编号。
 *
 * <p>以玩家视野为准，墙后的东西不报成看见；声音只给方位和远近。
 */
package org.maiwithu.maicraft.behavior.perception;
