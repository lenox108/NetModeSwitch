package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class SimPowerTest {

    private fun rule(id: Long = 1, sim: SimPower? = null) = AppRule(
        id = id, enabled = true, hour = 23, minute = 0, endHour = 7, endMinute = 0, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, trigger = Trigger.TIME, simPower = sim,
    )

    @Test fun encodesAndDecodes() {
        for (p in listOf(SimPower(0, true), SimPower(1, false))) assertEquals(p, SimPower.decode(p.encode()))
        assertNull(SimPower.decode(null))
        assertNull(SimPower.decode("x:1"))
        assertNull(SimPower.decode("-1:0"))
    }

    @Test fun aSimSwitchIsAnActionOfItsOwn() {
        assertFalse(rule().hasActions)
        val r = rule(sim = SimPower(1, false))
        assertTrue(r.hasActions)
        assertEquals("SIM 2 выкл", r.actionLabel)
    }

    @Test fun onlyItsOwnSlotIsCovered() {
        val r = rule(sim = SimPower(1, false))
        assertTrue(r.covers(FrozenApp.SIMPWR + 1))
        assertFalse(r.covers(FrozenApp.SIMPWR + 0))
        assertFalse(rule().covers(FrozenApp.SIMPWR + 1))
    }

    @Test fun opposingSwitchesOfOneSimConflict() {
        val off = rule(1, SimPower(1, false))
        assertEquals(listOf("SIM вкл/выкл"), off.conflicts(listOf(rule(2, SimPower(1, true)))).single().second)
        assertTrue(off.conflicts(listOf(rule(2, SimPower(0, true)))).isEmpty())
    }

    @Test fun eSimTargetsKeepCardAndPortForTheWayBack() {
        val esim = SimSwitch.Target(7, embedded = true, card = 2, port = 1)
        assertEquals("on:7:2:1", esim.prev(true))
        assertEquals(true to esim, SimSwitch.Target.fromPrev(esim.prev(true)))
        assertEquals(esim, SimSwitch.Target.decode(esim.encode()))
    }

    @Test fun physicalSimsKeepTheShortRecord() {
        val sim = SimSwitch.Target(3, embedded = false)
        assertEquals("off:3", sim.prev(false))
        assertEquals(false to sim, SimSwitch.Target.fromPrev("off:3"))
        assertNull(SimSwitch.Target.fromPrev("on"))
        assertNull(SimSwitch.Target.decode("x"))
    }
}
