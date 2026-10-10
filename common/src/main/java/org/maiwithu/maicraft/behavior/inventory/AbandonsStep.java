// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 腾挪一步的中途撒手：任务收尾或换地方时，正在等的确认不再等、开着的界面请游戏关上、正在走的路停下。
 *
 * <p>腾挪的读端是跨刻推进的有状态对象，但接缝方法（合并、存箱、丢出）不带收尾入口；
 * 实现这个接口的读端由腾地方统一在收尾时叫停，不留下悬着的界面或等着没结果的确认。
 */
interface AbandonsStep {

    /** 撒手当前这一步：已提交没确认的交互先记进没能确认的事实，再收掉手里的界面与动作。 */
    void abandonStep();
}
