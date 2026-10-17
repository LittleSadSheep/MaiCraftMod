// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.util.Objects;

import com.google.gson.JsonObject;

/**
 * 一张存起来的建筑图纸：编号、改自哪一版、图纸正文。图纸不绑地点，坐标相对设计原点；
 * 每次修改都是新的一版并记着父版本，旧版本不就地改。
 *
 * @param id       设计编号（UUID）
 * @param parentId 改自哪一版；第一版为 null
 * @param drawing  图纸正文
 */
public record BuildingDesign(String id, String parentId, JsonObject drawing) {

    public BuildingDesign {
        Objects.requireNonNull(id, "id");
        drawing = Objects.requireNonNull(drawing, "drawing").deepCopy();
    }

    /** 返回副本，改它不会碰存起来的那份。 */
    @Override
    public JsonObject drawing() {
        return drawing.deepCopy();
    }

    /** 图纸里写的名字；没写就是"未命名"。 */
    public String name() {
        return drawing.has("name") ? drawing.get("name").getAsString() : "未命名";
    }
}
