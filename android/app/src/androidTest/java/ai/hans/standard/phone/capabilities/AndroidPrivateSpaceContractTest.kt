package ai.hans.standard.phone.capabilities

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPrivateSpaceContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences(
        "private_space_contract_test",
        Context.MODE_PRIVATE,
    )

    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun visibilityPreferenceDefaultsVisibleAndPersistsHideAndReenable() {
        val store = SharedPreferencesPrivateSpaceContainerVisibilityStore(preferences)

        assertTrue(store.isVisible())
        assertTrue(store.setVisible(false))
        assertFalse(SharedPreferencesPrivateSpaceContainerVisibilityStore(preferences).isVisible())
        assertTrue(store.setVisible(true))
        assertTrue(SharedPreferencesPrivateSpaceContainerVisibilityStore(preferences).isVisible())
    }

    @Test
    fun lockAndUnlockRequestsUseOpaqueHandleAndReturnLiveReread() {
        val platform = FakeLauncherProfilePlatform(initialLocked = false)
        val adapter = AndroidPublicCapabilityAdapter(context, platform)
        val initial = adapter.privateSpaceSnapshot().successValue()
        val opaqueId = requireNotNull(initial.profile).profileId

        assertOpaqueProfileHandleFormat(opaqueId)
        val locked = adapter.requestPrivateSpaceLocked(opaqueId, true).successValue()
        assertEquals(PrivateSpaceQuietModeOutcome.CONFIRMED, locked.outcome)
        val lockedProfile = requireNotNull(locked.snapshot.profile)
        assertEquals(opaqueId, lockedProfile.profileId)
        assertTrue(lockedProfile.locked)
        val unlocked = adapter.requestPrivateSpaceLocked(opaqueId, false).successValue()
        assertEquals(PrivateSpaceQuietModeOutcome.CONFIRMED, unlocked.outcome)
        val unlockedProfile = requireNotNull(unlocked.snapshot.profile)
        assertEquals(opaqueId, unlockedProfile.profileId)
        assertFalse(unlockedProfile.locked)
        assertEquals(listOf(true, false), platform.quietModeRequests)
        assertTrue(platform.lockStateReads >= 4)
    }

    @Test
    fun opaqueProfileHandlesAreDerivedFromEntropyRatherThanAndroidIdentity() {
        val entropy = listOf(
            ByteArray(16) { index -> index.toByte() },
            ByteArray(16) { index -> (0x70 + index).toByte() },
        )
        var entropyReads = 0
        val registry = OpaqueProfileHandleRegistry<UserHandle> {
            entropy.getOrElse(entropyReads++) { error("unexpected_entropy_read") }.copyOf()
        }
        val firstUser = UserHandle.getUserHandleForUid(1_000_000)
        val secondUser = UserHandle.getUserHandleForUid(1_100_000)

        val firstHandle = registry.handleFor(firstUser)
        val secondHandle = registry.handleFor(secondUser)

        assertEquals("profile_000102030405060708090a0b0c0d0e0f", firstHandle)
        assertEquals("profile_707172737475767778797a7b7c7d7e7f", secondHandle)
        assertOpaqueProfileHandleFormat(firstHandle)
        assertOpaqueProfileHandleFormat(secondHandle)
        assertEquals(firstHandle, registry.handleFor(firstUser))
        assertEquals(secondHandle, registry.handleFor(secondUser))
        assertEquals(2, entropyReads)
    }

    @Test
    fun asynchronousAndCredentialUnlockOutcomesNeverChangeStateOptimistically() {
        val pendingPlatform = FakeLauncherProfilePlatform(
            initialLocked = true,
            mutateOnRequest = false,
            acceptRequest = true,
        )
        val pendingAdapter = AndroidPublicCapabilityAdapter(context, pendingPlatform)
        val pendingId = requireNotNull(
            pendingAdapter.privateSpaceSnapshot().successValue().profile,
        ).profileId

        val pending = pendingAdapter.requestPrivateSpaceLocked(pendingId, false).successValue()
        assertEquals(PrivateSpaceQuietModeOutcome.PENDING, pending.outcome)
        assertTrue(requireNotNull(pending.snapshot.profile).locked)

        val authPlatform = FakeLauncherProfilePlatform(
            initialLocked = true,
            mutateOnRequest = false,
            acceptRequest = false,
        )
        val authAdapter = AndroidPublicCapabilityAdapter(context, authPlatform)
        val authId = requireNotNull(authAdapter.privateSpaceSnapshot().successValue().profile).profileId
        val auth = authAdapter.requestPrivateSpaceLocked(authId, false).successValue()
        assertEquals(PrivateSpaceQuietModeOutcome.AUTHENTICATION_REQUIRED, auth.outcome)
        assertTrue(requireNotNull(auth.snapshot.profile).locked)
    }

    @Test
    fun platformAndRoleGatesFailClosedBeforeExposingPrivateProfile() {
        val oldPlatform = FakeLauncherProfilePlatform(apiLevel = 34)
        val oldAdapter = AndroidPublicCapabilityAdapter(context, oldPlatform)
        assertEquals(
            PrivateSpaceAvailability.UNSUPPORTED_PLATFORM,
            oldAdapter.privateSpaceSnapshot().successValue().availability,
        )
        assertFalse(
            oldAdapter.listLaunchableAppCatalog().successValue().profiles
                .any { it.type == LaunchProfileType.PRIVATE },
        )

        val noRolePlatform = FakeLauncherProfilePlatform(homeRole = false)
        val noRoleAdapter = AndroidPublicCapabilityAdapter(context, noRolePlatform)
        val state = noRoleAdapter.privateSpaceSnapshot().successValue()
        assertEquals(PrivateSpaceAvailability.HOME_ROLE_REQUIRED, state.availability)
        assertNull(state.profile)
        assertFalse(
            noRoleAdapter.listLaunchableAppCatalog().successValue().profiles
                .any { it.type == LaunchProfileType.PRIVATE },
        )

        // A denied platform lookup can make a hidden profile look "unknown". Unknown API 35+
        // profiles are omitted rather than exposed as an ordinary secondary user.
        val indeterminatePlatform = FakeLauncherProfilePlatform(
            homeRole = false,
            privateProfileType = LaunchProfileType.OTHER,
        )
        assertFalse(
            AndroidPublicCapabilityAdapter(context, indeterminatePlatform)
                .listLaunchableAppCatalog()
                .successValue()
                .profiles
                .any { it.profileId != PERSONAL_LAUNCH_PROFILE_ID },
        )
    }

    @Test
    fun androidSixteenPublicSettingsSenderIsGatedAndInvoked() {
        val platform = FakeLauncherProfilePlatform(
            settingsAvailable = true,
            entrypointHiddenWhenLocked = true,
        )
        val adapter = AndroidPublicCapabilityAdapter(context, platform)

        val snapshot = adapter.privateSpaceSnapshot().successValue()
        assertTrue(snapshot.settingsAvailable)
        assertTrue(snapshot.entrypointHiddenWhenLocked)
        assertTrue(adapter.openPrivateSpaceSettings() is AndroidAdapterResult.Success)
        assertEquals(1, platform.settingsOpenCount)
    }

    @Test
    fun profileBroadcastRequiresExtraUserAndTriggersRereadSignal() {
        val received = mutableListOf<LauncherProfileEvent>()
        val receiver = LauncherProfileEventReceiver(received::add)

        receiver.onReceive(context, Intent(Intent.ACTION_PROFILE_UNAVAILABLE))
        assertTrue(received.isEmpty())

        receiver.onReceive(
            context,
            Intent(Intent.ACTION_PROFILE_UNAVAILABLE)
                .putExtra(Intent.EXTRA_USER, UserHandle.getUserHandleForUid(1_000_000)),
        )
        receiver.onReceive(
            context,
            Intent(Intent.ACTION_PROFILE_ADDED)
                .putExtra(Intent.EXTRA_USER, UserHandle.getUserHandleForUid(1_000_000)),
        )
        receiver.onReceive(
            context,
            Intent(Intent.ACTION_PROFILE_INACCESSIBLE)
                .putExtra(Intent.EXTRA_USER, UserHandle.getUserHandleForUid(1_000_000)),
        )
        receiver.onReceive(
            context,
            Intent(Intent.ACTION_PROFILE_REMOVED)
                .putExtra(Intent.EXTRA_USER, UserHandle.getUserHandleForUid(1_000_000)),
        )

        assertEquals(
            listOf(
                LauncherProfileEventKind.UNAVAILABLE,
                LauncherProfileEventKind.ADDED,
                LauncherProfileEventKind.INACCESSIBLE,
                LauncherProfileEventKind.REMOVED,
            ),
            received.map(LauncherProfileEvent::kind),
        )
        assertTrue(received.first().requiresImmediateInventoryPurge)
        assertFalse(received[1].requiresImmediateInventoryPurge)
        assertTrue(received[2].requiresImmediateInventoryPurge)
        assertTrue(received[3].requiresImmediateInventoryPurge)
    }

    private fun <T> AndroidAdapterResult<T>.successValue(): T =
        (this as AndroidAdapterResult.Success<T>).value

    private fun assertOpaqueProfileHandleFormat(value: String) {
        assertTrue(
            "Expected a profile_ prefix followed by exactly 128 bits of lowercase hex entropy",
            value.matches(Regex("profile_[0-9a-f]{32}")),
        )
    }

    private class FakeLauncherProfilePlatform(
        override val apiLevel: Int = 36,
        private val homeRole: Boolean = true,
        private val hiddenPermission: Boolean = true,
        initialLocked: Boolean = false,
        private val mutateOnRequest: Boolean = true,
        private val acceptRequest: Boolean = true,
        private val settingsAvailable: Boolean = false,
        private val privateProfileType: LaunchProfileType = LaunchProfileType.PRIVATE,
        private val entrypointHiddenWhenLocked: Boolean = false,
    ) : AndroidLauncherProfilePlatform {
        private val current = Process.myUserHandle()
        val privateUser: UserHandle = UserHandle.getUserHandleForUid(1_000_000)
        var locked: Boolean = initialLocked
        var lockStateReads: Int = 0
        val quietModeRequests = mutableListOf<Boolean>()
        var settingsOpenCount: Int = 0

        override fun currentUser(): UserHandle = current

        override fun accessibleProfiles(): List<UserHandle> = listOf(current, privateUser)

        override fun profileType(
            user: UserHandle,
            current: UserHandle,
        ): LaunchProfileType = if (user == privateUser) {
            privateProfileType
        } else {
            LaunchProfileType.PERSONAL
        }

        override fun isProfileLocked(user: UserHandle, current: UserHandle): Boolean {
            if (user != privateUser) return false
            lockStateReads += 1
            return locked
        }

        override fun hasHomeRole(): Boolean = homeRole

        override fun hasHiddenProfilesPermission(): Boolean = hiddenPermission

        override fun requestQuietModeEnabled(locked: Boolean, user: UserHandle): Boolean {
            assertEquals(privateUser, user)
            quietModeRequests += locked
            if (mutateOnRequest && acceptRequest) this.locked = locked
            return acceptRequest
        }

        override fun privateSpaceEntrypointHiddenWhenLocked(user: UserHandle): Boolean {
            assertEquals(privateUser, user)
            return entrypointHiddenWhenLocked
        }

        override fun privateSpaceSettingsAvailable(): Boolean = settingsAvailable

        override fun openPrivateSpaceSettings(): Boolean {
            if (!settingsAvailable) return false
            settingsOpenCount += 1
            return true
        }
    }
}
