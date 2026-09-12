package ai.hans.standard

import android.Manifest
import ai.hans.standard.ui.CapabilityAccessUiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPlatformPermissionContractTest {
    @Test
    fun appNotificationsBecomeRuntimePermissionOnlyOnApi33() {
        assertTrue(
            SetupPlatformPermissionContract.permissionsFor(
                CapabilityAccessUiId.APP_NOTIFICATIONS,
                32,
            ).isEmpty(),
        )
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            SetupPlatformPermissionContract.permissionsFor(
                CapabilityAccessUiId.APP_NOTIFICATIONS,
                33,
            ),
        )
    }

    @Test
    fun mediaPermissionsUseLegacyApi31GranularApi33AndSelectedApi34Contracts() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            SetupPlatformPermissionContract.permissionsFor(
                CapabilityAccessUiId.PHOTOS_AND_VIDEOS,
                31,
            ),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            ),
            SetupPlatformPermissionContract.permissionsFor(
                CapabilityAccessUiId.PHOTOS_AND_VIDEOS,
                33,
            ),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            SetupPlatformPermissionContract.permissionsFor(
                CapabilityAccessUiId.PHOTOS_AND_VIDEOS,
                34,
            ),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_AUDIO),
            SetupPlatformPermissionContract.permissionsFor(
                CapabilityAccessUiId.AUDIO_MEDIA,
                33,
            ),
        )
    }

    @Test
    fun contactsCalendarLocationAreNarrowExplicitPermissionGroups() {
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            SetupPlatformPermissionContract.permissionsFor(CapabilityAccessUiId.CONTACTS, 31),
        )
        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            SetupPlatformPermissionContract.permissionsFor(CapabilityAccessUiId.CALENDAR, 31),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            SetupPlatformPermissionContract.permissionsFor(CapabilityAccessUiId.LOCATION, 31),
        )
    }
}
