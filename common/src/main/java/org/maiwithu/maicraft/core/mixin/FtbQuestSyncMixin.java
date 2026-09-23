// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import org.maiwithu.maicraft.core.integration.ftbquests.ReflectiveFtbQuestsAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原生同步结束后仅记录当前连接，不替换任务数据，不修改玩家进度；未安装 FTB 时跳过此钩子。 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.ClientQuestFile", remap = false)
public abstract class FtbQuestSyncMixin {
    @Inject(method = "syncFromServer", at = @At("RETURN"), remap = false, require = 0)
    private static void maicraft$observeQuestBook(CallbackInfo callback) { ReflectiveFtbQuestsAccess.recordSync(); }
}
