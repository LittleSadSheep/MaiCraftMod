// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 正在替角色应对威胁的任务的特征接口：任务自己声明现在还接得住眼前的威胁。
 *
 * <p>被攻击的生存需求据此让位：主任务本来就在还手、且评估不是"打不过"时，
 * 不再插一个自卫临时任务；血线跌破拒战线则由本能说了算，自卫照插。
 * 内核与需求不认识任何具体能力，只认这个接口。
 */
public interface ThreatResponder {

    /** 此刻是否还接得住眼前的威胁（自己的评估不是"打不过"，且血线没破）。 */
    boolean confidentAgainstCurrentThreats();
}
