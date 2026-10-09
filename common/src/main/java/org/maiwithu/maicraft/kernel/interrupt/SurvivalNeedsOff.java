// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 不要生存需求插手的任务的特征接口：这件事本身就要角色不自救（例如寻死），由任务许可的
 * {@code survival_needs=off} 表达。它是主任务时，控制循环一个生存需求都不插，不在打断规则里开后门。
 * 内核与需求不认识任何具体能力，只认这个接口。
 */
public interface SurvivalNeedsOff {

    /** 此刻是否关掉生存需求。 */
    boolean survivalNeedsOff();
}
