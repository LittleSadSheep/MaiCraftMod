// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.spi;

/** 收障碍来源的回答：一个结构交一次。 */
public interface ObstacleSink {

    void add(MovingStructure structure);
}
