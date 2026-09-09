/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.lunatrius.schematica.client.world;

import com.github.lunatrius.core.util.math.MBlockPos;
import com.github.lunatrius.schematica.api.ISchematic;

// Baritone 编译时需要的第三方类签名占位，根构建脚本会把 com/github/lunatrius 整包排除出发布物。
// 它不是可运行的示意图世界：构造时的强制转换和 getSchematic 的异常都是占位，不能在客户端实例化。
public class SchematicWorld {

    public final MBlockPos position = (MBlockPos) (Object) "cringe";

    public ISchematic getSchematic() {
        throw new LinkageError("LOL");
    }
}
