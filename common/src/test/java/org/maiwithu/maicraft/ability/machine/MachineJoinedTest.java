// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/** 接网核对的判断：同网才算接上；过载与转速为零是接上了没在转；读不到的不硬说。 */
class MachineJoinedTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MachineState state(MachineState.Activity activity, Double speed) {
        return new MachineState(activity, speed, null, List.of(), Map.of(), "");
    }

    @Test
    void 同网在转_接上且在转() {
        MachineJoined.Verdict verdict = MachineJoined.judge(Optional.of("net1"), Optional.of("net1"),
                state(MachineState.Activity.RUNNING, 120.0));
        assertTrue(verdict.joined());
        assertTrue(verdict.running());
    }

    @Test
    void 同网但过载_接上了没在转() {
        MachineJoined.Verdict verdict = MachineJoined.judge(Optional.of("net1"), Optional.of("net1"),
                state(MachineState.Activity.OVERLOADED, null));
        assertTrue(verdict.joined());
        assertFalse(verdict.running());
        assertTrue(verdict.note().contains("过载"));
    }

    @Test
    void 同网转速为零_接上了没在转() {
        MachineJoined.Verdict verdict = MachineJoined.judge(Optional.of("net1"), Optional.of("net1"),
                state(MachineState.Activity.IDLE, 0.0));
        assertTrue(verdict.joined());
        assertFalse(verdict.running());
    }

    @Test
    void 同网读不到运行状态_算接上不硬说转没转() {
        MachineJoined.Verdict verdict = MachineJoined.judge(Optional.of("net1"), Optional.of("net1"),
                MachineState.unknown("读不到"));
        assertTrue(verdict.joined());
        assertTrue(verdict.running(), "读不到时不冒充没在转");
    }

    @Test
    void 不同网或一边不在网_没接上() {
        assertFalse(MachineJoined.judge(Optional.of("net2"), Optional.of("net1"),
                state(MachineState.Activity.RUNNING, 120.0)).joined());
        assertFalse(MachineJoined.judge(Optional.empty(), Optional.of("net1"),
                state(MachineState.Activity.RUNNING, 120.0)).joined());
        assertFalse(MachineJoined.judge(Optional.of("net1"), Optional.empty(),
                state(MachineState.Activity.RUNNING, 120.0)).joined());
    }
}
