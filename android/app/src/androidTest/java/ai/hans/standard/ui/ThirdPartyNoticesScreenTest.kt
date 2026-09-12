package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test

/** In-memory assets only: no device settings, real app data or network. */
class ThirdPartyNoticesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listsComponentThenShowsUnmodifiedOriginalText() {
        val original = "Example license\n  unchanged whitespace"
        val hash = MessageDigest.getInstance("SHA-256").digest(original.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val index = """{"schema":"hans.third-party-notices.v1","components":[{"id":"test","label":"Example component","texts":[{"label":"LICENSE","asset":"hans/licenses/test.txt","sha256":"$hash"}]}]}"""
        val loader = ThirdPartyNoticesLoader { asset -> ByteArrayInputStream(
            (if (asset == ThirdPartyNoticesLoader.INDEX) index else original).toByteArray()) }
        compose.setContent { MaterialTheme { ThirdPartyNoticesScreen({}, loader) } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("LICENSE")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Example component").assertIsDisplayed()
        compose.onNodeWithText("LICENSE").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("third_party_notice_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("third_party_notice_original").assertTextEquals(original)
    }

    @Test fun missingIndexShowsHonestUnavailableState() {
        val loader = ThirdPartyNoticesLoader { throw java.io.FileNotFoundException() }
        compose.setContent { MaterialTheme { ThirdPartyNoticesScreen({}, loader) } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("third_party_notices_unavailable")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("third_party_notices_unavailable").assertIsDisplayed()
    }
}
