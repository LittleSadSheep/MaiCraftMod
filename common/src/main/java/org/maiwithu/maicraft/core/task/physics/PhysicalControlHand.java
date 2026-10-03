package org.maiwithu.maicraft.core.task.physics;

import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 先结清背包交换再选频率物品；缺料复用供料链，创造模式同样走原生领取。 */
final class PhysicalControlHand {
    private final FirstPersonActionGate selection=new FirstPersonActionGate();
    private String requested,failure;private Task supply;private boolean started;
    private int selectionRetries;
    private Map<String,Object> supplyEvidence=Map.of();
    boolean ready(LocalPlayer player,String itemId,String call,long deadline) {
        if(failure!=null)return false;
        if(itemId==null)return ClientRuntime.requireContext(player).menus().ensureWorldVisible(ClientRuntime.requireContext(player));
        if(!itemId.equals(requested)) {selection.reset();requested=itemId;}
        if(supply!=null) {
            if(!started) {supply.start(player);started=true;}
            TaskState state=supply.tick(player);if(!state.isTerminal())return false;
            var result=supply.result(state);supplyEvidence=result.data();supply=null;started=false;
            if(!result.success()) {failure=result.message();return false;}
        }
        if(selection.pending())return selected(player,selection.requestedSlot());
        var item=BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElseThrow(()->new IllegalArgumentException("未知控制物品: "+itemId));
        if(item==Items.AIR&&player.getMainHandItem().isEmpty())return ClientRuntime.requireContext(player).menus().ensureWorldVisible(ClientRuntime.requireContext(player));
        int slot=-1;
        if(item==Items.AIR) {for(int i=0;i<36;i++)if(player.getInventory().getItem(i).isEmpty()){slot=i;break;}}
        else slot=PlayerInv.findSlot(player.getInventory(),item);
        if(slot<0||slot>=36) {
            if(item==Items.AIR) {failure="没有可通过原生换槽腾出的空手位置";return false;}
            if(player.getAbilities().instabuild)supply=new CreativeTakeItemsCompanionTask(player,new CreativeTakeItemsTaskRecord(call,deadline,new ItemStack(item),1));
            else supply=new SemanticAcquireCompanionTask(player,new SemanticAcquireTaskRecord(call,deadline,List.of(BuiltInRegistries.ITEM.getKey(item)),1,
                    SemanticAcquireTaskRecord.DEFAULT_SOURCES,false,SemanticAcquireTaskRecord.SourceHint.empty(),List.of(),16).captureStorageOrigin(player));
            selection.reset();return false;
        }
        if(!selected(player,slot))return false;
        boolean matches=item==Items.AIR?player.getMainHandItem().isEmpty():player.getMainHandItem().is(item);
        if(!matches)selection.reset();else selectionRetries=0;
        return matches;
    }
    private boolean selected(LocalPlayer player,int slot) {
        var state=selection.select(player,slot);
        if(state==FirstPersonActionGate.Status.FAILED) {
            // 自卫或已结算的换槽改变了手持位置时重新读背包；未结算交换仍由同一个门控继续观察。
            if(!selection.pending()&&++selectionRetries<=2){selection.reset();return false;}
            failure=selection.failure();
        }
        return state==FirstPersonActionGate.Status.READY;
    }
    String failure(){return failure;}
    Map<String,Object> evidence(){return supplyEvidence;}
    Map<String,Object> progress(){return Map.of("requested_item",requested==null?"keep_current_hand":requested,
            "selection_pending",selection.pending(),"supply",supply==null?Map.of():supply.progress());}
    void stop(LocalPlayer player,Task.StopReason why){if(supply!=null)supply.stop(player,why);}
    void close(){if(supply!=null){supply.result(TaskState.CANCELLED);supply=null;}selection.reset();}
}
