package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class ModuleGuardsTest {
    @Test fun unknownAndUnrelatedSendersAreDenied() {
        assertFalse(ModuleGuards.authorizedSender(-1, 10245))
        assertFalse(ModuleGuards.authorizedSender(11111, 10245))
        assertFalse(ModuleGuards.authorizedSender(10245, null))
        assertTrue(ModuleGuards.authorizedSender(10245, 10245))
        assertTrue(ModuleGuards.authorizedSender(1000, 10245))
    }
    @Test fun arbitraryBroadcastCannotWriteAnotherUsersAppOps() {
        assertFalse(ModuleGuards.mayWriteUser(999, 10132, false))
        assertFalse(ModuleGuards.mayWriteUser(0, 99910132, false))
        assertFalse(ModuleGuards.mayWriteUser(-1, 1000, true))
        assertTrue(ModuleGuards.mayWriteUser(999, 99910132, false))
        assertTrue(ModuleGuards.mayWriteUser(999, 10132, true))
    }

    @Test fun autostartUsersAreConcreteAndThrottledSeparately() {
        assertTrue(ModuleGuards.validUser(0))
        assertTrue(ModuleGuards.validUser(999))
        assertFalse(ModuleGuards.validUser(-1))
        assertFalse(ModuleGuards.validUser(-2))
        assertFalse(ModuleGuards.validUser(Int.MAX_VALUE))
        assertNotEquals(ModuleGuards.autostartKey("a.b", 0), ModuleGuards.autostartKey("a.b", 999))
    }
}
