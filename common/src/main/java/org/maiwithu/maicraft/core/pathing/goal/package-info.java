/**
 * 把“走到方块旁边”“站上指定位置”等任务要求，转换为导航目标和需要保护的格子。
 * 其中区域目标与地形采样还用于寻找可站的平台；当前位置是否真正满足任务仍由执行层复查。
 */
package org.maiwithu.maicraft.core.pathing.goal;
