// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;

/**
 * 寻死看角色自己的只读接口：模式、死亡不掉落、血量与位置、身上带的东西、穿着的护甲。
 * 生产实现读当刻的本地玩家，测试给可以拨的替身。
 */
interface SuicidePlayer {

    /** 角色此刻在世界里。 */
    boolean present();

    /** 创造模式：死不了。 */
    boolean creative();

    /** 旁观模式：死不了。 */
    boolean spectator();

    /** 极限模式：死了不能重生。 */
    boolean hardcore();

    /** 死亡不掉落读出来是什么。 */
    KeepInventoryRule.Reading keepInventory();

    /** 这个角色重生过几次：比开始时多了就是死过了。 */
    long respawns();

    /** 此刻的血量。 */
    float health();

    /** 此刻的位置。 */
    Vec3 position();

    /** 此刻脚下那一格（脚位）。 */
    BlockPos feet();

    /** 此刻站在地上。 */
    boolean onGround();

    /** 此刻身上着火。 */
    boolean onFire();

    /** 此刻的摔伤估算：脱了护甲之后取样才准。 */
    FallDamage fall();

    /** 身上有抗火效果：岩浆与火伤不到人。 */
    boolean fireResistant();

    /** 身上（背包、快捷栏、副手）带着这种东西。 */
    boolean carries(String itemId);

    /** 这个栏位此刻穿着、拿着的那一件；空着为空。 */
    Optional<WornPiece> worn(GearSlotName slot);

    /** 这只手此刻拿着不死图腾。 */
    boolean holdsTotem(GearSlotName hand);

    /** 主背包与快捷栏（0~35）里和这件原件一模一样（附魔、耐久都一样）的那一格；没有为 -1。 */
    int carriedSlotOf(WornPiece piece);

    /**
     * 穿着的一件东西。
     *
     * @param itemId   物品 ID
     * @param original 那一件本身的快照（生产里是物品的拷贝，测试里是随便一个记号），只拿来和背包里的东西比是不是同一件
     */
    record WornPiece(String itemId, Object original) {}
}
