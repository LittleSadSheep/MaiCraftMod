// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 把建好、胶好的方块组装成会动的结构（或拆回方块）的一种办法：物理组装器拉一次杆、车站按组车按钮。
 * 联动模组实现它；组装能力只认这个接口，不认具体是哪个模组。
 */
public interface VehicleAssembler {

    /**
     * 一次组装或拆回的请求。
     *
     * @param assemble    组装（true）还是拆回（false）
     * @param near        组装器（或要组装的方块）大概在哪（世界坐标）；按结构指的时候是结构所在
     * @param structureId 指的是哪个结构（拆回时用；观察编号 s# 换来的结构身份）；没指结构为 null
     * @param permissions 这次的许可：别人的载具点名即许可，但保护照归属看
     */
    record Request(boolean assemble, BlockPos near, String structureId, Permissions permissions) {
        public Request {
            Objects.requireNonNull(near, "near");
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    /** 这里有没有这种办法能做的组装：有就给一次运行（走过去、拉一次、确认），没有为空。 */
    Optional<Run> start(Request request);

    /**
     * 一次组装运行：做完后读结局。
     */
    interface Run extends Action {

        /** 做完后的结局；还没做完时为 null。 */
        Conversion conversion();
    }

    /**
     * 结局：动作成没成与有没有变成结构分开说。
     *
     * @param alreadyDone 开始时就已经是要的样子（已经组装过 / 已经是方块），什么都没做
     * @param converted   拉了之后真的转换了
     * @param structureId 组装出来（或拆回前）的结构身份；没有为空串
     * @param blocks      组装进结构的方块数；拆回或不知道为 -1
     * @param note        一句经过：拉了几次、等了多久、没转换时看到的原因
     */
    record Conversion(boolean alreadyDone, boolean converted, String structureId, int blocks, String note) {}
}
