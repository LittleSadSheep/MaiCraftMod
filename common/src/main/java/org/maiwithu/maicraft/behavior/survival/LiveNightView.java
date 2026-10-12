// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜晚处境的生产读取：到了能睡的时间没有、身在何处算不算安全、现在的威胁评估是什么。
 *
 * <p>"安全处"按看得到的光读：头顶真被盖住（屋里、矿道）、或身边光照充足（火把边）就算安全；
 * 露天、树荫下、岩檐下都算露天。威胁评估直接用战斗感观现算一遍——夜里露天要不要躲，靠的就是它。
 */
public final class LiveNightView implements NightfallNeed.ReadsNight {

    /** 天光掉到这一级及以下算头顶盖住了：只隔一片树叶、一块岩檐的地方天光还有 13、14。 */
    private static final int COVERED_SKY_LIGHT = 7;
    /** 方块光到这一级及以上算灯够亮。 */
    private static final int LIT_BLOCK_LIGHT = 8;

    /** 一晚上露天迟早会碰上的：一只僵尸、一只骷髅，都还在远处、没盯上角色。 */
    private static final List<ThreatAssessment.Foe> TYPICAL_NIGHT_PACK = List.of(
            new ThreatAssessment.Foe(16, ThreatAssessment.Kind.MELEE, false, false),
            new ThreatAssessment.Foe(16, ThreatAssessment.Kind.RANGED, false, false));

    private final CombatSenses senses;

    public LiveNightView(CombatSenses senses) {
        this.senses = senses;
    }

    @Override
    public NightfallNeed.Facts read(TickContext context) {
        var player = context.player();
        ClientLevel level = player == null ? null : player.level();
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (level == null || self == null) {
            return null;
        }
        boolean sleepTime = WorldTime.canAttemptSleep(level);
        BlockPos eye = BlockPos.containing(self.getEyePosition());
        int sky = level.getBrightness(LightLayer.SKY, eye);
        int block = level.getBrightness(LightLayer.BLOCK, eye);
        // 安全处：头顶真被盖住（天光掉到 7 及以下：屋里、矿道深处）或灯够亮（方块光 8 以上，怪刷不出来）。
        // 树荫下、悬崖下天光只掉一两级，怪照样刷、照样走过来，不算安全处。
        boolean inShelter = sky <= COVERED_SKY_LIGHT || block >= LIT_BLOCK_LIGHT;
        var profile = senses.profile(context);
        ArrayList<ThreatAssessment.Foe> foes = new ArrayList<>();
        for (CombatSenses.Threat threat : senses.threats(context, ThreatAssessment.VIGILANCE_RADIUS)) {
            foes.add(new ThreatAssessment.Foe(threat.distance(), threat.kind(), threat.armed(), threat.armed()));
        }
        // 眼下还没有怪：按一晚上迟早会碰上的一只僵尸一只骷髅估——空手（镐不算兵器）的身板
        // 等怪真到了跟前再躲就晚了，天一黑就挖坑；拿着剑、斧的照常干活，来了再打。
        if (foes.isEmpty()) foes.addAll(TYPICAL_NIGHT_PACK);
        var mine = new ThreatAssessment.MySide(self.getHealth(), self.getArmorValue(),
                profile.weapon().map(WeaponChoice::scoreOf).orElse(0), profile.foodCount(), true);
        ThreatAssessment.Verdict verdict = ThreatAssessment.assess(mine, foes).verdict();
        // 今晚有没有床由睡觉能力的找床读端回答，NightfallNeed 把两份处境合起来分流。
        return new NightfallNeed.Facts(sleepTime, inShelter, verdict, self.getHealth(), self.getArmorValue());
    }
}
