package ai.hans.standard.backup

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSafContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun exportUsesPublicCreateDocumentContractOnly() {
        val intent = ActivityResultContracts.CreateDocument(HansBackupDocumentCodec.MIME_TYPE)
            .createIntent(context, "hans-backup${HansBackupDocumentCodec.FILE_EXTENSION}")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(HansBackupDocumentCodec.MIME_TYPE, intent.type)
        assertEquals(
            "hans-backup${HansBackupDocumentCodec.FILE_EXTENSION}",
            intent.getStringExtra(Intent.EXTRA_TITLE),
        )
    }

    @Test
    fun importUsesPublicOpenDocumentContractOnly() {
        val intent = ActivityResultContracts.OpenDocument().createIntent(
            context,
            arrayOf(HansBackupDocumentCodec.MIME_TYPE, "application/json"),
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(
            arrayOf(HansBackupDocumentCodec.MIME_TYPE, "application/json"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
    }
}
