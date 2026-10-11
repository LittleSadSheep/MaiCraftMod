// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 跨维度行程的现场部件：找门、走进门、建门、末地门房间与继续步行用到的活，启动时拼好一份交给任务。
 * 服务都从构造函数传入，不写静态单例；替身测试只替这里面的接缝。
 *
 * @param world           跨维度的现场只读视图：维度、找活门、门活没活、身体沾没沾门格、扫黑曜石与框架
 * @param context         每刻的角色上下文（每刻取一份）：直走进门时取移动与视角写入入口
 * @param walks           走到：走近门口、走向离门落脚格、走到要塞线索与空眼旁
 * @param obtain          拿东西的内需入口：备打火石（没有则火弹）、备末影之眼
 * @param toMainhand      换到主手：点火与插眼前把用品换好
 * @param uses            对一格方块右键使用：点火、插眼
 * @param construction    按许可拼一份施工服务：门框蓝图交给施工引擎用
 * @param memory          世界记忆：末地找要塞查记过的产地线索
 * @param placedBlocks    走到垫过的方块读端：继续步行段结算用
 * @param progressListener 出行进展的去处：继续步行段的进度从这里报
 */
public record DimensionTravelServices(DimensionWorldView world, Supplier<PlayerContext> context, WalkTo walks,
        ItemNeeds obtain, MovesToMainhand toMainhand, UsesBlocks uses,
        Function<Permissions, ConstructionServices> construction, WorldMemory memory,
        ReadsPlacedBlocks placedBlocks, TravelProgressListener progressListener) {
}
