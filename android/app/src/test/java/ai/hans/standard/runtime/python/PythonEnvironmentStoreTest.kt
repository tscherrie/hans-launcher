package ai.hans.standard.runtime.python

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PythonEnvironmentStoreTest {
    @Test
    fun installsSelfTestsAndActivatesADeterministicReadOnlyEnvironment() {
        val fixture = fixture()
        val source = File(fixture.root, "plugin-input").apply { mkdirs() }
        File(source, "entry.py").writeText("def run():\n    return 42\n")
        val sourceDigest = PythonEnvironmentSourceDigest.digest(source)
        val wheel = createWheel(fixture.root, "demo-pkg", "1.0.0", mapOf(
            "demo_pkg/__init__.py" to "VALUE = 42\n",
        ))
        val pin = pin(wheel, "demo-pkg", "1.0.0")
        var prepared: PythonPreparedEnvironment? = null
        val store = fixture.store(PythonEnvironmentImportSelfTester {
            prepared = it
            PythonImportSelfTestResult(true, it.importNames)
        })

        val status = store.installAndActivate(
            installRequest(lock("garage", listOf(pin), sourceDigest), wheel, source),
        )

        assertEquals(PythonEnvironmentState.ACTIVE, status.state)
        assertNotEquals(PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST, store.digestFor("garage"))
        assertEquals(
            PythonEnvironmentStore.RESOLUTION_PROOF_EXACT_ARTIFACTS,
            status.detail,
        )
        assertEquals(setOf("demo_pkg"), prepared!!.importNames)
        val receipt = store.open(store.digestFor("garage"))
        assertEquals(receipt.sha256, PythonEnvironmentContract.sha256(receipt.archive.readBytes()))
        ZipFile(receipt.archive).use { zip ->
            assertEquals(ZipEntry.STORED, zip.getEntry("demo_pkg/__init__.py").method)
            assertEquals(ZipEntry.STORED, zip.getEntry("__hans_plugin_source__/entry.py").method)
            assertEquals(ZipEntry.STORED, zip.getEntry("__hans_environment__.json").method)
        }
        assertTrue(store.snapshot().environments.single().state == PythonEnvironmentState.ACTIVE)
    }

    @Test
    fun isolatedDescriptorBrokerCanOpenTheStagedArchiveWhileInstallerWaitsForSelfTest() {
        val fixture = fixture()
        val wheel = createWheel(
            fixture.root,
            "demo-pkg",
            "1.0.0",
            mapOf("demo_pkg/__init__.py" to "VALUE = 42\n"),
        )
        val pin = pin(wheel, "demo-pkg", "1.0.0")
        val brokerThread = Executors.newSingleThreadExecutor()
        lateinit var store: PythonEnvironmentStore
        try {
            store = fixture.store(PythonEnvironmentImportSelfTester { prepared ->
                val digest = PythonEnvironmentContract.sha256(
                    prepared.environmentArchive.readBytes(),
                )
                val opened = brokerThread.submit<PythonEnvironmentArchiveReceipt> {
                    store.open(digest)
                }.get(2, TimeUnit.SECONDS)
                assertEquals(digest, opened.sha256)
                assertEquals(prepared.environmentArchive.canonicalFile, opened.archive)
                PythonImportSelfTestResult(true, prepared.importNames)
            })

            val receipt = store.prepareInstall(
                installRequest(lock("descriptor-proof", listOf(pin)), wheel),
            )
            store.rollbackActivation(receipt)
        } finally {
            brokerThread.shutdownNow()
        }
    }

    @Test
    fun identicalInputsProduceTheSameArchiveDigest() {
        val first = fixture()
        val second = fixture()
        val firstWheel = createWheel(first.root, "demo-pkg", "1.0.0", emptyMap())
        val secondWheel = File(second.root, firstWheel.name).also { firstWheel.copyTo(it) }
        val firstPin = pin(firstWheel, "demo-pkg", "1.0.0")
        val secondPin = pin(secondWheel, "demo-pkg", "1.0.0")

        first.store(successfulSelfTest()).installAndActivate(
            installRequest(lock("demo", listOf(firstPin)), firstWheel),
        )
        second.store(successfulSelfTest()).installAndActivate(
            installRequest(lock("demo", listOf(secondPin)), secondWheel),
        )

        assertEquals(
            first.store(successfulSelfTest()).digestFor("demo"),
            second.store(successfulSelfTest()).digestFor("demo"),
        )
    }

    @Test
    fun failedSelfTestRollsBackToPreviouslyActiveVersion() {
        val fixture = fixture()
        val selfTestSucceeds = AtomicBoolean(true)
        val store = fixture.store(PythonEnvironmentImportSelfTester {
            PythonImportSelfTestResult(
                succeeded = selfTestSucceeds.get(),
                importedNames = if (selfTestSucceeds.get()) it.importNames else emptySet(),
                errorCode = if (selfTestSucceeds.get()) null else "import_failed",
            )
        })
        val firstWheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val firstPin = pin(firstWheel, "demo-pkg", "1.0.0")
        store.installAndActivate(installRequest(lock("demo", listOf(firstPin)), firstWheel))
        val firstDigest = store.digestFor("demo")
        val secondWheel = createWheel(fixture.root, "demo-pkg", "2.0.0", emptyMap())
        val secondPin = pin(secondWheel, "demo-pkg", "2.0.0")
        selfTestSucceeds.set(false)

        expectFailure("self-test") {
            store.installAndActivate(installRequest(lock("demo", listOf(secondPin)), secondWheel))
        }

        assertEquals(firstDigest, store.digestFor("demo"))
        assertEquals(1, store.snapshot().environments.size)
    }

    @Test
    fun prepareDoesNotActivateAndCommittedReceiptCanRestoreTheExactPreviousPointer() {
        val fixture = fixture()
        val store = fixture.store(successfulSelfTest())
        val firstWheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val firstPin = pin(firstWheel, "demo-pkg", "1.0.0")
        store.installAndActivate(installRequest(lock("demo", listOf(firstPin)), firstWheel))
        val firstDigest = store.digestFor("demo")
        val secondWheel = createWheel(fixture.root, "demo-pkg", "2.0.0", emptyMap())
        val secondPin = pin(secondWheel, "demo-pkg", "2.0.0")

        val receipt = store.prepareInstall(
            installRequest(lock("demo", listOf(secondPin)), secondWheel),
        )
        assertEquals(firstDigest, store.digestFor("demo"))
        assertEquals(
            listOf(PythonEnvironmentState.INSTALLED, PythonEnvironmentState.ACTIVE),
            store.snapshot().environments.map { it.state }.sortedBy { it.ordinal },
        )

        store.commitActivation(receipt)
        assertEquals(firstDigest, store.digestFor("demo"))
        store.rollbackActivation(receipt)
        assertEquals(firstDigest, store.digestFor("demo"))
    }

    @Test
    fun preparedTransactionSurvivesProcessDeathAndCanCommitThenFinalizeExactlyOnce() {
        val fixture = fixture()
        val firstWheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val firstPin = pin(firstWheel, "demo-pkg", "1.0.0")
        fixture.store(successfulSelfTest()).installAndActivate(
            installRequest(lock("demo", listOf(firstPin)), firstWheel),
        )
        val firstDigest = fixture.store(successfulSelfTest()).digestFor("demo")
        val secondWheel = createWheel(fixture.root, "demo-pkg", "2.0.0", emptyMap())
        val secondPin = pin(secondWheel, "demo-pkg", "2.0.0")
        fixture.store(successfulSelfTest()).prepareInstall(
            installRequest(lock("demo", listOf(secondPin)), secondWheel),
        )

        val recoveredStore = fixture.store(successfulSelfTest())
        val recovery = recoveredStore.recoveryDescriptors().single()
        assertEquals(PythonEnvironmentRecoveryState.PREPARED, recovery.state)
        assertEquals(firstDigest, recovery.previous!!.environmentDigest)
        assertEquals(firstDigest, recoveredStore.digestFor("demo"))

        recoveredStore.commitActivation(recovery.receipt)
        assertEquals(firstDigest, recoveredStore.digestFor("demo"))
        expectFailure("not finalized") { recoveredStore.open(recovery.installed.environmentDigest) }
        recoveredStore.finalizeActivation(recovery.receipt)
        assertEquals(recovery.installed.environmentDigest, recoveredStore.digestFor("demo"))
        assertTrue(recoveredStore.recoveryDescriptors().isEmpty())
        expectFailure("unknown or completed") {
            recoveredStore.finalizeActivation(recovery.receipt)
        }
        expectFailure("unknown or completed") {
            recoveredStore.rollbackActivation(recovery.receipt)
        }
    }

    @Test
    fun committedTransactionIsQuarantinedAfterProcessDeathAndCanRollbackExactlyOnce() {
        val fixture = fixture()
        val firstWheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val firstPin = pin(firstWheel, "demo-pkg", "1.0.0")
        val original = fixture.store(successfulSelfTest())
        original.installAndActivate(installRequest(lock("demo", listOf(firstPin)), firstWheel))
        val firstDigest = original.digestFor("demo")
        val secondWheel = createWheel(fixture.root, "demo-pkg", "2.0.0", emptyMap())
        val secondPin = pin(secondWheel, "demo-pkg", "2.0.0")
        val receipt = original.prepareInstall(
            installRequest(lock("demo", listOf(secondPin)), secondWheel),
        )
        original.commitActivation(receipt)
        assertEquals(firstDigest, original.digestFor("demo"))

        val recoveredStore = fixture.store(successfulSelfTest())
        val recovery = recoveredStore.recoveryDescriptors().single()
        assertEquals(PythonEnvironmentRecoveryState.COMMITTED, recovery.state)
        assertEquals(firstDigest, recoveredStore.digestFor("demo"))
        expectFailure("not finalized") { recoveredStore.open(recovery.installed.environmentDigest) }

        recoveredStore.rollbackActivation(recovery.receipt)
        assertEquals(firstDigest, recoveredStore.digestFor("demo"))
        assertTrue(recoveredStore.recoveryDescriptors().isEmpty())
        expectFailure("unknown or completed") {
            recoveredStore.rollbackActivation(recovery.receipt)
        }
        assertFalse(recovery.installed.lockDigest in
            recoveredStore.snapshot().environments.mapNotNull { it.lockDigest })
    }

    @Test
    fun corruptOversizeAndSymlinkActivationJournalsFailClosed() {
        fun preparedFixture(): Fixture {
            val fixture = fixture()
            val wheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
            val pin = pin(wheel, "demo-pkg", "1.0.0")
            fixture.store(successfulSelfTest()).prepareInstall(
                installRequest(lock("demo", listOf(pin)), wheel),
            )
            return fixture
        }

        val corrupt = preparedFixture()
        journalFiles(corrupt).single().writeText("{not-json")
        expectFailure("malformed") { corrupt.store(successfulSelfTest()) }

        val oversized = preparedFixture()
        RandomAccessFile(journalFiles(oversized).single(), "rw").use {
            it.setLength(PythonEnvironmentActivationJournalCodec.MAX_BYTES.toLong() + 1)
        }
        expectFailure("size limit") { oversized.store(successfulSelfTest()) }

        val linked = fixture()
        val journalDirectory = File(linked.storeRoot, "activation-journal").apply { mkdirs() }
        val outside = File(linked.root, "outside-journal.json").apply { writeText("{}") }
        val symlink = File(journalDirectory, "pyenv-${"a".repeat(32)}.json")
        runCatching { Files.createSymbolicLink(symlink.toPath(), outside.toPath()) }
            .getOrElse { return }
        expectFailure("regular file") { linked.store(successfulSelfTest()) }
    }

    @Test
    fun orphanedPreparedVersionWithoutJournalIsCollectedBeforeQuotaAccounting() {
        val fixture = fixture()
        val wheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val pin = pin(wheel, "demo-pkg", "1.0.0")
        val store = fixture.store(successfulSelfTest())
        val receipt = store.prepareInstall(installRequest(lock("demo", listOf(pin)), wheel))
        val environmentDirectory = File(
            File(File(fixture.storeRoot, "versions/demo"), receipt.target.directorySegment),
            receipt.lockDigest,
        )
        assertTrue(environmentDirectory.isDirectory)
        assertTrue(journalFiles(fixture).single().delete())

        val recovered = fixture.store(successfulSelfTest())

        assertFalse(environmentDirectory.exists())
        assertTrue(recovered.recoveryDescriptors().isEmpty())
        assertEquals(PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST, recovered.digestFor("demo"))
        val replacementWheel = createWheel(fixture.root, "replacement", "1.0.0", emptyMap())
        val replacementPin = pin(replacementWheel, "replacement", "1.0.0")
        recovered.installAndActivate(
            installRequest(lock("replacement", listOf(replacementPin)), replacementWheel),
        )
        assertFalse(recovered.digestFor("replacement") ==
            PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST)
    }

    @Test
    fun activationWriterFailureLeavesPreviousPointerUntouched() {
        val fixture = fixture()
        val writes = AtomicInteger()
        val writer = PythonEnvironmentAtomicFileWriter { target, bytes ->
            if (writes.incrementAndGet() == 2) error("simulated atomic activation failure")
            target.writeBytes(bytes)
        }
        val store = PythonEnvironmentStore(
            rootDirectory = fixture.storeRoot,
            wheelValidator = PythonWheelArchiveValidator(36, setOf("arm64-v8a")),
            importSelfTester = successfulSelfTest(),
            atomicWriter = writer,
        )
        val firstWheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val firstPin = pin(firstWheel, "demo-pkg", "1.0.0")
        store.installAndActivate(installRequest(lock("demo", listOf(firstPin)), firstWheel))
        val firstDigest = store.digestFor("demo")
        val secondWheel = createWheel(fixture.root, "demo-pkg", "2.0.0", emptyMap())
        val secondPin = pin(secondWheel, "demo-pkg", "2.0.0")
        val receipt = store.prepareInstall(
            installRequest(lock("demo", listOf(secondPin)), secondWheel),
        )

        expectFailure("atomic activation") { store.commitActivation(receipt) }
        assertEquals(firstDigest, store.digestFor("demo"))
        store.rollbackActivation(receipt)
        assertEquals(firstDigest, store.digestFor("demo"))
    }

    @Test
    fun rejectsCorruptWheelAndLeavesNoPartialTransaction() {
        val fixture = fixture()
        val wheel = createWheel(fixture.root, "demo-pkg", "1.0.0", emptyMap())
        val badPin = PythonWheelPin(
            packageName = "demo-pkg",
            version = "1.0.0",
            fileName = wheel.name,
            sha256 = "a".repeat(64),
            sizeBytes = wheel.length(),
            sourceUri = "file://${wheel.name}",
        )
        val store = fixture.store(successfulSelfTest())

        expectFailure("digest") {
            store.installAndActivate(installRequest(lock("demo", listOf(badPin)), wheel, pin = badPin))
        }

        assertEquals(PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST, store.digestFor("demo"))
        assertTrue(File(fixture.storeRoot, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsCrossWheelCollisionsAndZipSlip() {
        val collision = fixture()
        val one = createWheel(
            collision.root,
            "first-pkg",
            "1.0.0",
            mapOf("shared.py" to "ONE = 1\n"),
        )
        val two = createWheel(
            collision.root,
            "second-pkg",
            "1.0.0",
            mapOf("shared.py" to "TWO = 2\n"),
        )
        val onePin = pin(one, "first-pkg", "1.0.0")
        val twoPin = pin(two, "second-pkg", "1.0.0")
        expectFailure("collide") {
            collision.store(successfulSelfTest()).installAndActivate(
                PythonEnvironmentInstallRequest(
                    lock("collision", listOf(onePin, twoPin)),
                    PythonOfflineWheelSet.of(mapOf(onePin.sha256 to one, twoPin.sha256 to two)),
                ),
            )
        }

        val traversal = fixture()
        val malicious = createWheel(
            traversal.root,
            "evil-pkg",
            "1.0.0",
            mapOf("../escape.py" to "ESCAPED = True\n"),
        )
        val maliciousPin = pin(malicious, "evil-pkg", "1.0.0")
        expectFailure("traversal") {
            traversal.store(successfulSelfTest()).installAndActivate(
                installRequest(lock("evil", listOf(maliciousPin)), malicious),
            )
        }
        assertFalse(File(traversal.root, "escape.py").exists())
    }

    @Test
    fun rejectsSourceMismatchAndSymlinks() {
        val fixture = fixture()
        val source = File(fixture.root, "source").apply { mkdirs() }
        File(source, "entry.py").writeText("VALUE = 1\n")
        val store = fixture.store(successfulSelfTest())

        expectFailure("source digest") {
            store.installAndActivate(
                PythonEnvironmentInstallRequest(
                    lock("source", emptyList(), sourceSha256 = "b".repeat(64)),
                    PythonOfflineWheelSet.EMPTY,
                    source,
                ),
            )
        }

        val outside = File(fixture.root, "outside.py").apply { writeText("VALUE = 2\n") }
        val link = File(source, "link.py")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.getOrElse { return }
        expectFailure("symlink") { PythonEnvironmentSourceDigest.digest(source) }
    }

    @Test
    fun cancellationRemovesPartialStagingAndCrashRecoveryRemovesOrphans() {
        val fixture = fixture()
        val wheel = createWheel(
            fixture.root,
            "demo-pkg",
            "1.0.0",
            (0..20).associate { "demo_pkg/file_$it.py" to "VALUE = $it\n" },
        )
        val pin = pin(wheel, "demo-pkg", "1.0.0")
        val checks = AtomicInteger()
        val cancellation = PythonEnvironmentInstallCancellation { checks.incrementAndGet() > 7 }

        try {
            fixture.store(successfulSelfTest()).installAndActivate(
                installRequest(lock("cancelled", listOf(pin)), wheel),
                cancellation,
            )
            fail("Expected cancellation")
        } catch (_: PythonEnvironmentInstallCancelledException) {
            // expected
        }
        assertTrue(File(fixture.storeRoot, "staging").listFiles().orEmpty().isEmpty())

        val orphan = File(fixture.storeRoot, "staging/.staging-crash").apply { mkdirs() }
        File(orphan, "partial").writeText("partial")
        val temporaryPointer = File(fixture.storeRoot, "active/.tmp-crash").apply { writeText("partial") }
        fixture.store(successfulSelfTest())
        assertFalse(orphan.exists())
        assertFalse(temporaryPointer.exists())
    }

    @Test
    fun nativePackageMustMatchTheSignedCatalogExactly() {
        val fixture = fixture()
        val pin = PythonNativeCatalogPin("sqlite-addon", "1.0", "sqlite", "c".repeat(64))
        val lock = PythonEnvironmentLock(
            schemaVersion = 1,
            pluginId = "native",
            target = TARGET,
            wheels = emptyList(),
            nativePackages = listOf(pin),
        )
        val wrongCatalog = PythonNativePackageCatalog { _, _ ->
            PythonNativeCatalogEntry(
                catalogId = "sqlite",
                packageName = "sqlite-addon",
                version = "1.0",
                payloadSha256 = "d".repeat(64),
                sourceSdistSha256 = "e".repeat(64),
                supportedTargets = setOf(TARGET.directorySegment),
                importNames = setOf("sqlite_addon"),
                companionWheel = PythonNativeCompanionWheel(
                    assetPath = "hans/python/native-packages/sqlite-addon-1.0-py3-none-any.whl",
                    fileName = "sqlite_addon-1.0-py3-none-any.whl",
                    sha256 = "f".repeat(64),
                    sizeBytes = 1,
                    source = PythonSignedAssetSource { ByteArrayInputStream(byteArrayOf(0)) },
                ),
                nativeLibraries = listOf(
                    PythonNativeLibraryPin(
                        moduleName = "sqlite_addon",
                        packagedName = "libhans_py_sqlite_addon.so",
                        sha256 = "1".repeat(64),
                        sizeBytes = 1,
                    ),
                ),
            )
        }
        expectFailure("catalog pin") {
            fixture.store(successfulSelfTest(), wrongCatalog).installAndActivate(
                PythonEnvironmentInstallRequest(lock, PythonOfflineWheelSet.EMPTY),
            )
        }
    }

    @Test
    fun lockedNativePackageExtractsOnlyItsSignedCompanionAndTransportsExactAuthority() {
        val fixture = fixture()
        val companion = createWheel(
            fixture.root,
            "sqlite-addon",
            "1.0",
            mapOf("sqlite_addon/__init__.py" to "VALUE = 7\n"),
        )
        val companionBytes = companion.readBytes()
        val payload = "c".repeat(64)
        val nativePin = PythonNativeCatalogPin("sqlite-addon", "1.0", "sqlite", payload)
        val entry = PythonNativeCatalogEntry(
            catalogId = "sqlite",
            packageName = "sqlite-addon",
            version = "1.0",
            payloadSha256 = payload,
            sourceSdistSha256 = "d".repeat(64),
            supportedTargets = setOf(TARGET.directorySegment),
            importNames = setOf("sqlite_addon", "sqlite_addon._native"),
            requiresPython = ">=3.14",
            companionWheel = PythonNativeCompanionWheel(
                assetPath = "hans/python/native-packages/${companion.name}",
                fileName = companion.name,
                sha256 = PythonEnvironmentContract.sha256(companionBytes),
                sizeBytes = companionBytes.size.toLong(),
                source = PythonSignedAssetSource { ByteArrayInputStream(companionBytes) },
            ),
            nativeLibraries = listOf(
                PythonNativeLibraryPin(
                    moduleName = "sqlite_addon._native",
                    packagedName = "libhans_py_sqlite_addon___native.so",
                    sha256 = "e".repeat(64),
                    sizeBytes = 17,
                ),
            ),
        )
        val catalog = PythonNativePackageCatalog { pin, target ->
            entry.takeIf {
                pin == nativePin && target == TARGET
            }
        }
        val store = fixture.store(successfulSelfTest(), catalog)
        val status = store.installAndActivate(
            PythonEnvironmentInstallRequest(
                lock = PythonEnvironmentLock(
                    schemaVersion = 1,
                    pluginId = "native-companion",
                    target = TARGET,
                    wheels = emptyList(),
                    nativePackages = listOf(nativePin),
                ),
                offlineWheels = PythonOfflineWheelSet.EMPTY,
            ),
        )

        assertEquals(PythonEnvironmentState.ACTIVE, status.state)
        val receipt = store.open(store.digestFor("native-companion"))
        assertEquals(
            listOf(
                PythonAllowedNativeModule(
                    "sqlite_addon._native",
                    "libhans_py_sqlite_addon___native.so",
                ),
            ),
            receipt.allowedNativeModules,
        )
        ZipFile(receipt.archive).use { archive ->
            assertTrue(archive.getEntry("sqlite_addon/__init__.py") != null)
            assertTrue(archive.getEntry(".native-sqlite.whl") == null)
        }
    }

    private data class Fixture(val root: File, val storeRoot: File) {
        fun store(
            selfTester: PythonEnvironmentImportSelfTester,
            catalog: PythonNativePackageCatalog = PythonNativePackageCatalog.EMPTY,
        ) = PythonEnvironmentStore(
            rootDirectory = storeRoot,
            wheelValidator = PythonWheelArchiveValidator(36, setOf("arm64-v8a")),
            importSelfTester = selfTester,
            nativeCatalog = catalog,
        )
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("hans-python-environment").toFile()
        return Fixture(root, File(root, "store"))
    }

    private fun journalFiles(fixture: Fixture): List<File> =
        File(fixture.storeRoot, "activation-journal").listFiles().orEmpty()
            .filter { it.name.endsWith(PythonEnvironmentActivationJournalCodec.FILE_SUFFIX) }

    private fun successfulSelfTest() = PythonEnvironmentImportSelfTester {
        PythonImportSelfTestResult(true, it.importNames)
    }

    private fun lock(
        pluginId: String,
        wheels: List<PythonWheelPin>,
        sourceSha256: String? = null,
    ) = PythonEnvironmentLock(
        schemaVersion = 1,
        pluginId = pluginId,
        target = TARGET,
        wheels = wheels,
        sourceSha256 = sourceSha256,
    )

    private fun installRequest(
        lock: PythonEnvironmentLock,
        wheel: File,
        source: File? = null,
        pin: PythonWheelPin = lock.wheels.single(),
    ) = PythonEnvironmentInstallRequest(
        lock = lock,
        offlineWheels = PythonOfflineWheelSet.of(mapOf(pin.sha256 to wheel)),
        pluginSourceDirectory = source,
    )

    private fun pin(file: File, packageName: String, version: String) = PythonWheelPin(
        packageName = packageName,
        version = version,
        fileName = file.name,
        sha256 = PythonEnvironmentContract.sha256(file.readBytes()),
        sizeBytes = file.length(),
        sourceUri = "file://${file.name}",
    )

    private fun createWheel(
        directory: File,
        packageName: String,
        version: String,
        packageFiles: Map<String, String>,
    ): File {
        val distribution = packageName.replace('-', '_')
        val file = File(directory, "$distribution-$version-py3-none-any.whl")
        val distInfo = "$distribution-$version.dist-info"
        val entries = linkedMapOf(
            "$distInfo/METADATA" to """
                Metadata-Version: 2.1
                Name: $packageName
                Version: $version
                Requires-Python: >=3.14

            """.trimIndent(),
            "$distInfo/WHEEL" to """
                Wheel-Version: 1.0
                Generator: Hans test
                Root-Is-Purelib: true
                Tag: py3-none-any

            """.trimIndent(),
            "$distInfo/top_level.txt" to distribution + "\n",
        )
        entries.putAll(packageFiles.ifEmpty { mapOf("$distribution/__init__.py" to "") })
        FileOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    private fun expectFailure(messagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing: $messagePart")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "Expected '${error.message}' to contain '$messagePart'",
                error.message.orEmpty().contains(messagePart, ignoreCase = true),
            )
        } catch (error: IllegalStateException) {
            assertTrue(
                "Expected '${error.message}' to contain '$messagePart'",
                error.message.orEmpty().contains(messagePart, ignoreCase = true),
            )
        }
    }

    private companion object {
        val TARGET = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)
    }
}
