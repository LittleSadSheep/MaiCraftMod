// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonObject;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/** 只读展示索引与按页正文分开：发现匹配条目时不展开配方树，也不创建菜单或合成操作。 */
public interface EmiRecipeAccess {
    Query query(LocalPlayer player, ResourceLocation itemId, boolean uses);

    /** 索引只保存当前EMI管理器的读取会话；重载后失效，不能把前后两次配方混成同一页。 */
    record Query(String status, String detail, String revision, int total,
                 IntFunction<JsonObject> reader, BooleanSupplier current) {
        public static Query unavailable(String status, String detail) {
            return new Query(status, detail, "", 0, ignored -> { throw new IllegalStateException("no EMI catalog"); }, () -> true);
        }
    }
}
