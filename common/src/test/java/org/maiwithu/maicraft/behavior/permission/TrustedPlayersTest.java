// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自家人名单：写 UUID 的直接认；写名字的对上在线玩家才认，对上一次就记住；没对上的不算。 */
class TrustedPlayersTest {

    private static final String ALICE_ID = UUID.randomUUID().toString();
    private static final String BOB_ID = UUID.randomUUID().toString();

    @Test
    void 写UUID的直接认_大小写不论() {
        TrustedPlayers trusted = new TrustedPlayers(List.of(ALICE_ID.toUpperCase()), name -> Optional.empty());
        assertTrue(trusted.includes(ALICE_ID));
        assertFalse(trusted.includes(BOB_ID));
    }

    @Test
    void 写名字的对上在线玩家才认_对上一次下线了也认() {
        Map<String, String> online = new HashMap<>(Map.of("bob", BOB_ID));
        TrustedPlayers trusted = new TrustedPlayers(List.of("Bob"), name -> Optional.ofNullable(online.get(name)));
        assertTrue(trusted.includes(BOB_ID));
        online.clear();
        assertTrue(trusted.includes(BOB_ID), "对上过一次就记住");
        assertFalse(new TrustedPlayers(List.of("carol"), name -> Optional.empty()).includes(BOB_ID),
                "对不上在线玩家的名字不算自家人");
        assertTrue(TrustedPlayers.NOBODY.isEmpty());
    }
}
