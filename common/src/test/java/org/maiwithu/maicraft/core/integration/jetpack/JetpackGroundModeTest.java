package org.maiwithu.maicraft.core.integration.jetpack;

import org.maiwithu.maicraft.client.actor.NativeActionReceipt.Status;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackGroundMode.Decision;

public final class JetpackGroundModeTest {
    public static void main(String[] args) {
        var active=new JetpackNativeAdapter.Snapshot(true,"fixture","create_jetpack:netherite_jetpack",true,true,900,17000,.016,.32,.6,-.03,.08);
        var off=new JetpackNativeAdapter.Snapshot(true,"fixture","create_jetpack:netherite_jetpack",false,true,900,17000,.016,.32,.6,-.03,.08);
        check(JetpackGroundMode.decide(active,true,0,false,null)==Decision.DISABLE,"ground travel disables the pack before leaving support");
        check(JetpackGroundMode.decide(active,false,-.03,false,null)==Decision.DISABLE,"prepared self-rescue exits slow hover");
        check(JetpackGroundMode.decide(active,false,-.9,false,null)==Decision.READY,"a fast fall keeps its urgent native placement slot");
        check(JetpackGroundMode.decide(off,true,0,true,Status.PENDING)==Decision.WAIT,"pending mode switch is not submitted twice");
        check(JetpackGroundMode.decide(off,true,0,true,Status.CONFIRMED_APPLIED)==Decision.READY,"confirmed disable permits departure");
        check(JetpackGroundMode.decide(active,true,0,true,Status.CONFIRMED_APPLIED)==Decision.FAILED,"do not fight a later manual reactivation");
        check(JetpackGroundMode.decide(off,true,0,true,Status.UNCERTAIN)==Decision.FAILED,"an uncertain mode receipt cannot authorize grounded departure");
        check(JetpackGroundMode.decide(JetpackNativeAdapter.Snapshot.unavailable("absent"),true,0,false,null)==Decision.READY,"ordinary players need no flight integration");
        System.out.println("JetpackGroundModeTest: passed");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
