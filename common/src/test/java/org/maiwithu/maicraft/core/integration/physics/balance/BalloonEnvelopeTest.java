package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.HashMap;
import static org.maiwithu.maicraft.core.integration.physics.balance.BalloonEnvelope.*;
import static org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceRegression.*;

/** 在副本里封顶、补侧壁和拆蒙皮，验证浮力来自有效气体容积而非蒙皮方块数量。 */
final class BalloonEnvelopeTest {
    static void run() {
        var blocks = new HashMap<Cell, Kind>();
        for (int x=0;x<5;x++) for(int y=0;y<5;y++) for(int z=0;z<5;z++)
            if (x==0 || x==4 || z==0 || z==4 || y==4) blocks.put(new Cell(x,y,z),Kind.AIRTIGHT);
        var bounds = new Bounds(new Cell(-1,-1,-1),new Cell(5,5,5));
        var source = new Cell(2,1,2);
        var closed = inspect(p -> blocks.getOrDefault(p,Kind.AIR),bounds,source,4096);
        check(closed.state().equals("enclosed") && closed.capacity()==36, "开口底部不应让上方容气区域失效");
        blocks.remove(new Cell(2,4,2));
        check(inspect(p -> blocks.getOrDefault(p,Kind.AIR),bounds,source,4096).state().equals("leaking"), "顶部破洞应失去容气能力");
        blocks.put(new Cell(2,4,2),Kind.AIRTIGHT); blocks.put(new Cell(2,2,2),Kind.SOLID);
        check(inspect(p -> blocks.getOrDefault(p,Kind.AIR),bounds,source,4096).capacity()==35, "非气密实心部件占据气体体积");
        check(inspect(p -> Kind.UNKNOWN,bounds,source,4096).state().equals("unloaded"), "未加载区域不能被当成封闭蒙皮");
        check(inspect(p -> blocks.getOrDefault(p,Kind.AIR),bounds,source,1).state().equals("budget_exhausted"), "预算不足必须保留未知");
    }
}
