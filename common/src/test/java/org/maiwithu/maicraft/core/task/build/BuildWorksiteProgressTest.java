// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationStep;

public final class BuildWorksiteProgressTest {
    public static void main(String[] args) {
        waitsDoNotSpendMovementTime();
        repeatedEdgesDoNotRenewProgress();
        aReturnOverKnownGroundRemainsProgress();
        anAscendingDetourMayMoveAwayFromTheWorksite();
        repeatedPlanningWithoutMovementIsBounded();
        System.out.println("BuildWorksiteProgressTest: actual driving, directed returns, detours and bounded loops passed");
    }
    private static void waitsDoNotSpendMovementTime() {
        var progress=new BuildWorksiteProgress();var edge=edge(0,1);var feet=new Vec3(.5,1,.5);var goal=new Vec3(10.5,1,.5);
        for(int tick=0;tick<100;tick++)check(!progress.observe(feet,goal,tick,edge),"initial approach allowance");
        int before=progress.stagnantTicks();
        // 普通等待不花驱动额度，但无实际进展的长等待必须另行到期，不能把九百刻原地算路解释成正常暂停。
        for(int tick=100;tick<200;tick++)check(!progress.observe(feet,goal,tick,null),"ordinary planning and native receipts retain their allowance");
        boolean idle=false;for(int tick=200;tick<1000;tick++)idle|=progress.observe(feet,goal,tick,null);
        check(idle,"planning without body or confirmed construction progress must eventually expire");
        check(progress.stagnantTicks()==before,"waiting must preserve the allowance rather than count or replenish it");
        check(!progress.observe(feet,goal,999,edge)&&progress.stagnantTicks()==before,"a duplicate client tick cannot be charged twice");
        boolean stalled=false;for(int tick=1000;tick<1110;tick++)stalled|=progress.observe(feet,goal,tick,edge);
        check(stalled,"a truly blocked moving attempt eventually expires");
        progress.changed(BlockPos.ZERO);check(!progress.observe(feet,goal,1111,edge),"confirmed construction renews the approach");
    }
    private static void repeatedEdgesDoNotRenewProgress() {
        var progress=new BuildWorksiteProgress();var goal=new Vec3(12.5,1,.5);boolean stalled=false;
        for(int tick=0;tick<500;tick++) {
            int offset=tick%12;boolean forward=offset<6;double fraction=(offset%6)/6.0;
            double x=forward?fraction:1-fraction;
            stalled|=progress.observe(new Vec3(x+.5,1,.5),goal,tick,forward?edge(0,1):edge(1,0));
        }
        check(stalled,"repeated A/B movement and identical replanned edges cannot reset the timer forever");
    }
    private static void aReturnOverKnownGroundRemainsProgress() {
        var progress=new BuildWorksiteProgress();var goal=new Vec3(.5,1,.5);int tick=0;
        for(int x=0;x<40;x++)for(int part=0;part<6;part++)
            check(!progress.observe(new Vec3(x+.5+part/6.0,1,.5),goal,tick++,edge(x,x+1)),"outbound route advances");
        for(int x=40;x>0;x--)for(int part=0;part<6;part++)
            check(!progress.observe(new Vec3(x+.5-part/6.0,1,.5),goal,tick++,edge(x,x-1)),"returning through familiar cells must remain valid progress");
    }
    private static void anAscendingDetourMayMoveAwayFromTheWorksite() {
        var progress=new BuildWorksiteProgress();var goal=new Vec3(.5,20,.5);int tick=0;
        for(int x=0;x<60;x++)for(int part=0;part<6;part++)
            check(!progress.observe(new Vec3(x+.5+part/6.0,1,.5),goal,tick++,edge(x,x+1)),"a proven ramp approach may first increase straight-line distance");
    }
    private static void repeatedPlanningWithoutMovementIsBounded() {
        var progress=new BuildWorksiteProgress();var goal=new Vec3(12.5,8,.5);boolean stalled=false;
        // 重算有时交回一个新首步但身体仍没动；新节点编号和细微站位噪声均不能重置整个工作站的等待上限。
        for(int tick=0;tick<450;tick++)stalled|=progress.observe(new Vec3(.5+(tick%2)*.001,1,.5),goal,tick,
                tick%10==0?edge(tick,tick+1):null);
        check(stalled,"new route identities cannot renew motionless construction");
        progress.changed(new BlockPos(1,0,0));
        check(!progress.observe(new Vec3(.5,1,.5),goal,451,null),"a genuinely confirmed new support renews the worksite");
    }
    private static NavigationStep edge(int from,int to){return new NavigationStep(new BlockPos(from,1,0),new BlockPos(to,1,0));}
    private static void check(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
}
