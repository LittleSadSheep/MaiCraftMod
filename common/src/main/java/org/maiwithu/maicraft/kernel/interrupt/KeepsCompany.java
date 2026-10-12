// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 陪着一个人的任务的特征接口：任务声明自己正陪着一个人，并说出那人此刻睡没睡。
 *
 * <p>夜晚这项生存需求据此决定去不去睡：陪着的人还醒着就不自己走开去睡——人家在赶路、在干活，
 * 陪的人跑去找床就跟丢了；他躺下了，才去附近找床一起睡。内核与需求不认识任何具体能力，只认这个接口。
 */
public interface KeepsCompany {

    /** 陪着的那个人此刻是不是躺在床上睡着；看不见他时为 false。 */
    boolean companionAsleep();
}
