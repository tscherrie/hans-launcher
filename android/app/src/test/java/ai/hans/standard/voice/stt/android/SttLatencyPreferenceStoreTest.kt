package ai.hans.standard.voice.stt.android

import ai.hans.standard.voice.stt.SttTranscriptionDelay
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttLatencyPreferenceStoreTest {
    @Test
    fun failedAtomicWriteKeepsTheLastCommittedValueForEveryStoreInstance() {
        val storage = FakeAtomicStorage()
        val launcher = SttLatencyPreferenceStore(storage)
        val service = SttLatencyPreferenceStore(storage)
        launcher.save(SttTranscriptionDelay.MINIMAL)
        storage.failWrites = true

        assertTrue(runCatching { launcher.save(SttTranscriptionDelay.LOW) }.isFailure)

        assertEquals(SttTranscriptionDelay.MINIMAL, launcher.read())
        assertEquals(SttTranscriptionDelay.MINIMAL, service.read())
        assertEquals(SttTranscriptionDelay.MINIMAL, SttLatencyPreferenceStore(storage).read())
        assertEquals("minimal", storage.committed?.toString(Charsets.US_ASCII))
    }

    @Test
    fun failedFirstWriteKeepsTheAbsentPreferenceAndLowDefault() {
        val storage = FakeAtomicStorage().apply { failWrites = true }
        val store = SttLatencyPreferenceStore(storage)
        assertTrue(runCatching { store.save(SttTranscriptionDelay.MINIMAL) }.isFailure)
        assertEquals(null, storage.committed)
        assertEquals(SttTranscriptionDelay.LOW, store.read())
    }

    @Test
    fun saveRequiresExactPersistedBytesRatherThanAChangedInMemoryPreference() {
        val storage = FakeAtomicStorage().apply { ignoreWrites = true }
        val store = SttLatencyPreferenceStore(storage)
        assertTrue(runCatching { store.save(SttTranscriptionDelay.MINIMAL) }.isFailure)
        assertTrue(runCatching { store.save(SttTranscriptionDelay.LOW) }.isFailure)
        assertEquals(null, storage.committed)
        assertEquals(SttTranscriptionDelay.LOW, store.read())
    }

    @Test
    fun anotherStoreCannotReadAnUnfinishedWriteEvenWithDifferentBackendInstances() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val observed = AtomicReference<SttTranscriptionDelay?>(null)
        val storage = FakeAtomicStorage()
        val writerStore = SttLatencyPreferenceStore(object : SttLatencyPreferenceStorage {
            override fun read(): ByteArray? = storage.read()
            override fun writeAtomically(bytes: ByteArray) {
                writeStarted.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                storage.writeAtomically(bytes)
            }
        })
        val readerStore = SttLatencyPreferenceStore(storage)
        val writer = Thread {
            runCatching { writerStore.save(SttTranscriptionDelay.MINIMAL) }
                .onFailure { failure.compareAndSet(null, it) }
        }
        val reader = Thread {
            readerStarted.countDown()
            runCatching { observed.set(readerStore.read()) }
                .onFailure { failure.compareAndSet(null, it) }
            readerDone.countDown()
        }
        try {
            writer.start()
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            reader.start()
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(readerDone.await(100, TimeUnit.MILLISECONDS))
            releaseWrite.countDown()
            assertTrue(readerDone.await(5, TimeUnit.SECONDS))
            writer.join(5_000)
            reader.join(5_000)
            assertEquals(null, failure.get())
            assertEquals(SttTranscriptionDelay.MINIMAL, observed.get())
        } finally {
            releaseWrite.countDown()
            writer.join(5_000)
            reader.join(5_000)
        }
    }

    @Test
    fun onlySmallExactWireValuesAreAcceptedFromTheFile() {
        val storage = FakeAtomicStorage()
        val store = SttLatencyPreferenceStore(storage)
        SttTranscriptionDelay.entries.forEach { delay ->
            assertEquals(delay, store.save(delay))
            assertEquals(delay.wireValue, storage.committed?.toString(Charsets.US_ASCII))
            assertEquals(delay, SttLatencyPreferenceStore(storage).read())
        }
        storage.committed = "minimal".repeat(100).toByteArray()
        assertEquals(SttTranscriptionDelay.LOW, store.read())
        assertEquals(700, storage.committed?.size)
    }

    @Test
    fun absentPreferenceUsesLowWithoutInventingAServerConfirmation() {
        assertEquals(SttTranscriptionDelay.LOW, SttLatencyPreferenceStore.decode(null))
    }

    @Test
    fun supportedWireValuesDecodeExactly() {
        assertEquals(SttTranscriptionDelay.LOW, SttLatencyPreferenceStore.decode("low"))
        assertEquals(SttTranscriptionDelay.MINIMAL, SttLatencyPreferenceStore.decode("minimal"))
    }

    @Test
    fun invalidUnsupportedOrMalformedValuesFallBackToLow() {
        listOf("", " ", "MINIMAL", " minimal ", "medium", "high", "xhigh", "null", "minimal\u0000")
            .forEach { stored ->
                assertEquals("Stored value: $stored", SttTranscriptionDelay.LOW, SttLatencyPreferenceStore.decode(stored))
            }
    }

    @Test
    fun everyOfferedPreferenceRoundTripsThroughItsPersistedWireValue() {
        SttTranscriptionDelay.entries.forEach { delay ->
            assertEquals(delay, SttLatencyPreferenceStore.decode(delay.wireValue))
        }
    }

    private class FakeAtomicStorage : SttLatencyPreferenceStorage {
        var committed: ByteArray? = null
        var failWrites = false
        var ignoreWrites = false

        override fun read(): ByteArray? = committed?.copyOf()

        override fun writeAtomically(bytes: ByteArray) {
            val staged = bytes.copyOf()
            if (failWrites) throw IOException("test_atomic_write_failed")
            if (!ignoreWrites) committed = staged
        }
    }
}
