package ai.hans.standard.automations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Separate host phases; neither test manufactures a restart or executes an automation directly. */
@RunWith(AndroidJUnit4::class)
class AndroidAutomationProcessRecoveryTest {
    private lateinit var fixture: AutomationProcessRecoveryInstance

    @Before fun requireExplicitOwnedFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Only an explicitly owned process-recovery emulator may arm this fixture",
            arguments.getString("hansAutomationProcessRecovery") == "true")
        fixture = AutomationProcessRecoveryFixture.requireActive()
        assertEquals(fixture.descriptor.fixtureId, arguments.getString("hansAutomationFixtureId"))
        AutomationProcessRecoveryFixture.assertProductionStoresAbsent(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test fun arm() = fixture.arm()

    @Test fun verify() = fixture.verify()
}
