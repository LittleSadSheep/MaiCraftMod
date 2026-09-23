// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 章节内的说明图片也可能承载玩法提示；只读已解锁图片的文字、布局和引用，不打开链接或触发点击。 */
final class FtbChapterDetails {
    private FtbChapterDetails() {}
    static JsonObject read(Object chapter, Object team, Set<String> visibleIds) {
        JsonObject result = new JsonObject(); result.add("group", identity(call(chapter, "getGroup")));
        result.addProperty("completed", flag(team, "isCompleted", chapter));
        result.addProperty("started", flag(team, "isStarted", chapter));
        JsonArray images = new JsonArray();
        for (Object image : (Iterable<?>) call(chapter, "getImages")) {
            if (!flag(image, "shouldShowImage", team)) continue;
            JsonObject row = identity(image); CompoundTag data = FtbQuestData.definition(image);
            // 图片出现并不授权读取隐藏前置的编号，移除对玩家不可见的内部依赖引用。
            if (data.contains("dependency") && !visibleIds.contains(data.getString("dependency"))) data.remove("dependency");
            row.add("presentation", FtbQuestData.json(data)); row.addProperty("image_reference", data.getString("image")); images.add(row);
        }
        result.add("images", images); return result;
    }
}
