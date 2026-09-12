package ai.hans.standard.artifacts

import android.os.ParcelFileDescriptor

/** Read-only Binder-safe lease. The caller owns and must close the descriptor. */
class AndroidArtifactReadLease(private val store: AtomicArtifactStore) {
    fun open(handle: ArtifactHandle): ParcelFileDescriptor = ParcelFileDescriptor.open(
        store.fileForReadLease(handle),
        ParcelFileDescriptor.MODE_READ_ONLY,
    )
}
