# Hans Standard media input

This package is the root-free, Android-12+ media boundary. It never retains a
picker URI, invokes a shell, loads an executable codec, or owns an API key.

## Safety and resource contract

- The picker grant is consumed immediately into an app-private transaction.
- Provider MIME, filename and size are treated as untrusted metadata.
- The actual image must decode and the actual video must contain a video track.
- Images are capped at 20 MiB and 64 megapixels, then normalized to bounded
  JPEG. Videos are capped at 128 MiB, 15 minutes and 40 megapixels.
- A video produces at most five 1280-edge JPEG frames. AAC/AMR audio is copied
  losslessly into a bounded M4A file. Other audio codecs produce an explicit
  source-track handoff for a MediaExtractor/MediaCodec or upload adapter.
- A hidden staging directory is renamed only after every required artifact and
  `manifest.json` is durable. Failure closes and removes the staging tree
  without following symbolic links.

## Launcher hooks

The Activity integration deliberately remains outside this package. The exact
hooks are:

1. Construct once after the private workspace is known:

   `AndroidMediaImportPipeline.forWorkspace(this, sessionStore.workspacePath)`

2. Register the direct camera capture through the existing
   `TakePicture`/FileProvider path. In the result callback, immediately call
   `pipeline.import(uri)` on the media executor. Hans intentionally has no
   separate gallery affordance. Do not request or rely on a persisted URI
   permission.

3. For `MediaImportResult.Image`, append one `HansPendingAttachment` whose path
   is `result.image.absolutePath`. On removal or cancelled composition, call
   `pipeline.delete(result.importId)` rather than deleting one file.

4. For `MediaImportResult.Video`, append each `representativeFrames[*].image`
   as a local-image input and include the bounded metadata in the user message.
   Call `result.toTranscriptionRequest()` and pass the request to the existing
   STT adapter through `VideoAudioTranscriptionGateway`. Its callback returns a
   completed transcript or a non-sensitive failure state and its handle cancels
   abandoned work. Add only the completed transcript to the turn. Never pass
   the source video to `CodexInput.LocalImage`.

5. Keep the import until the turn and STT handoff have reached a terminal state.
   After the correlated SENT receipt, register its import id in the atomic
   `MediaTurnLeaseTracker` under the exact thread/turn pair. Delete only after
   the matching terminal receipt (including the bounded, item-free receipts
   rebuilt by `thread/resume`) and confirm that the import is absent. Call
   `cleanupAbandonedImports()` at a safe lifecycle point; it only removes
   hidden, incomplete transactions.

6. Map `MediaImportException.code` to short localized UI text. Do not expose
   provider paths, internal filenames or exception messages to the chat model.

The returned `VideoAudioTranscriptionRequest` contains either a standalone M4A
or the private source container plus an exact audio track index. This is the
only STT seam; the media package performs no network request.
