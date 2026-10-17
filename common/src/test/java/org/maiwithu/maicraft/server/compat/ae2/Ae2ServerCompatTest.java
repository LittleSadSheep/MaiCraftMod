// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/** AE2 的服务端联动入口：登记只读操作 ae2.network，汇总写成回答的字段名与客户端读的一致。 */
class Ae2ServerCompatTest {

    @Test
    void 登记只读操作ae2点network() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        new ServerCompatRegistry(operations).add(new Ae2ServerCompat((level, at) -> Optional.empty()));
        assertTrue(operations.registered(Ae2ServerCompat.OPERATION));
    }

    @Test
    void 汇总写成回答_没控制器频道超了也照写() {
        JsonObject answer = Ae2ServerCompat.json(new Ae2GridReads.GridSummary("1a2b", 12, "none", false, "default",
                0, 8, "too_many_channels", true, 1200.5, 8000, 37, 4096, 1, 0));
        assertEquals(true, answer.get("found").getAsBoolean());
        assertEquals("none", answer.get("controller").getAsString());
        assertEquals(8, answer.get("ad_hoc_limit").getAsInt());
        assertEquals("too_many_channels", answer.get("ad_hoc_problem").getAsString());
        assertEquals(37, answer.get("stored_types").getAsInt());
        assertEquals(4096L, answer.get("stored_amount").getAsLong());
    }
}
