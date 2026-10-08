// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

/**
 * 环境视线接缝：读时间、天气、光照、生物群系。实现留给游戏接口层，测试用替身。
 */
public interface EnvironmentSight {

    /** 此刻的环境观察。 */
    SceneEnvironment current();
}
