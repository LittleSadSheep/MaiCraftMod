// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;

import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜晚处境的生产读取：到了能睡的时间没有、身在何处算不算安全、现在的威胁评估是什么。
 *
 * <p>"安全处"按看得到的光读：不在天空直射下、或身边光照充足（屋里、矿道、火把边）就算安全；
 * 露天无遮蔽就是露天。威胁评估直接用战斗感观现算一遍——夜里露天要不要躲，靠的就是它。
 */
public final class LiveNightView implements NightfallNeed.ReadsNight {

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
        boolean inShelter = sky < 15 || block >= 8;
        var profile = senses.profile(context);
        ArrayList<ThreatAssessment.Foe> foes = new ArrayList<>();
        for (CombatSenses.Threat threat : senses.threats(context, ThreatAssessment.VIGILANCE_RADIUS)) {
            foes.add(new ThreatAssessment.Foe(threat.distance(), threat.kind(), threat.armed(), threat.armed()));
        }
        var mine = new ThreatAssessment.MySide(self.getHealth(), self.getArmorValue(),
                profile.weapon().map(picked -> WeaponChoice.scoreOf(picked.weapon())).orElse(0), profile.foodCount(), true);
        ThreatAssessment.Verdict verdict = ThreatAssessment.assess(mine, foes).verdict();
        // 床的寻找与选床归睡觉规格；这一轨没合进来时先按"弄不到床"分流，事件里会说明。
        return new NightfallNeed.Facts(sleepTime, inShelter, false, verdict, self.getHealth(), self.getArmorValue());
    }
}
