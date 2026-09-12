package ai.hans.standard.phone.consent

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicPersistentAndroidConsentStoreTest {
    @Test
    fun everydayInventoryContainsOnlyRecurringReadsAndVisibleHandOffs() {
        assertEquals(
            setOf(
                PersistentAndroidConsentScope.INSTALLED_APPS_READ,
                PersistentAndroidConsentScope.OPEN_APP,
                PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION,
                PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE,
                PersistentAndroidConsentScope.READ_LOCATION,
                PersistentAndroidConsentScope.READ_CONTACTS,
                PersistentAndroidConsentScope.READ_CALENDAR,
                PersistentAndroidConsentScope.READ_MEDIA,
                PersistentAndroidConsentScope.READ_SENSORS,
                PersistentAndroidConsentScope.READ_REPLYABLE_NOTIFICATIONS,
                PersistentAndroidConsentScope.OPEN_CAMERA,
                PersistentAndroidConsentScope.PREPARE_CALENDAR_EVENT,
            ),
            PersistentAndroidConsentCatalog.EVERYDAY_SCOPES,
        )
        assertEquals(
            PersistentAndroidConsentCatalog.EVERYDAY_SCOPES,
            PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS.mapTo(linkedSetOf()) {
                it.scope
            },
        )
    }

    @Test
    fun persistRestoreScopeIsolationAndRevocationAreExact() {
        withStore { file, store ->
            val apps = category(PersistentAndroidConsentScope.INSTALLED_APPS_READ)
            val location = category(PersistentAndroidConsentScope.READ_LOCATION)

            assertTrue(store.grant(apps))
            assertTrue(store.contains(apps))
            assertFalse(store.contains(location))
            assertEquals(setOf(apps), AtomicPersistentAndroidConsentStore(file).active())

            assertTrue(store.grant(location))
            assertEquals(setOf(apps, location), AtomicPersistentAndroidConsentStore(file).active())
            assertTrue(store.revoke(apps))
            assertFalse(store.contains(apps))
            assertTrue(store.contains(location))
            assertTrue(store.revokeAll())
            assertTrue(AtomicPersistentAndroidConsentStore(file).active().isEmpty())
        }
    }

    @Test
    fun corruptUnknownAndVersionMismatchFilesFailClosedAsAWhole() {
        withStore { file, store ->
            val apps = category(PersistentAndroidConsentScope.INSTALLED_APPS_READ)
            assertTrue(store.grant(apps))

            file.writeText("not json")
            assertTrue(store.active().isEmpty())
            assertFalse(store.contains(apps))

            file.writeText(
                """{"schemaVersion":3,"policyVersion":2,"consents":[]}""",
            )
            assertTrue(store.active().isEmpty())

            file.writeText(
                """{"schemaVersion":2,"policyVersion":3,"consents":[]}""",
            )
            assertTrue(store.active().isEmpty())

            file.writeText(
                """{"schemaVersion":2,"policyVersion":2,"consents":[{"scope":"future_scope"}]}""",
            )
            assertTrue(store.active().isEmpty())

            file.writeText(
                """{"schemaVersion":2,"policyVersion":2,"consents":[],"unknown":true}""",
            )
            assertTrue(store.active().isEmpty())
        }
    }

    @Test
    fun everydayBundleIsAtomicCompleteVersionedAndIndividuallyRevocable() {
        withStore { file, store ->
            val one = category(PersistentAndroidConsentScope.INSTALLED_APPS_READ)
            assertTrue(store.grant(one))
            assertFalse(store.hasEverydayBundle())

            assertTrue(store.grantAll(PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS))
            assertTrue(store.hasEverydayBundle())
            assertEquals(
                PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS,
                AtomicPersistentAndroidConsentStore(file).active(),
            )

            assertTrue(store.revoke(one))
            assertFalse(store.hasEverydayBundle())
            assertTrue(store.grantAll(PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS))
            assertTrue(store.hasEverydayBundle())
        }
    }

    @Test
    fun corruptStateCannotBePartiallyRepairedByBundleGrant() {
        withStore { file, store ->
            assertTrue(store.grant(category(PersistentAndroidConsentScope.READ_LOCATION)))
            val corrupt = "{not-json"
            file.writeText(corrupt)

            assertFalse(store.grantAll(PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS))
            assertEquals(corrupt, file.readText())
            assertTrue(store.active().isEmpty())
            assertFalse(store.hasEverydayBundle())
        }
    }

    private fun category(
        scope: PersistentAndroidConsentScope,
    ) = PersistentAndroidConsentDescriptor.category(scope)

    private fun withStore(
        block: (java.io.File, AtomicPersistentAndroidConsentStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("hans-android-consent").toFile()
        try {
            val file = directory.resolve(AtomicPersistentAndroidConsentStore.FILE_NAME)
            block(file, AtomicPersistentAndroidConsentStore(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}
