# Hans Standard backup contract

The public document format is `ai.hans.standard.backup` version 1 and uses the
Storage Access Framework. It is a portable settings document, not a clone of
the app-private Codex home.

Included:

- non-secret Hans preferences;
- only the confirmed personal-profile summary;
- automation definitions, retargeted to independent threads when necessary;
- plugin and skill identifiers plus enabled choices;
- the abstract `appPrivateDefault` workspace selector.

Never represented:

- ChatGPT, API, OAuth or Android Keystore credentials;
- Codex thread/session/transcript data;
- notification content;
- automation runs, leases, receipts or per-run confirmations;
- root, Android-permission or UI-action consent receipts;
- absolute app-private paths or device-bound hardware key codes.

The SHA-256 field detects corruption and unintentional edits. It is not a
signature and does not establish who created a document. Import therefore
uses strict schema and bounds validation, a conflict preview, a process-local
one-time confirmation token and a private before-image recovery journal.
Model and reasoning-effort choices are staged through the ordinary App Server
path and are not presented as effective until that server confirms them.

Imported plugin choices are durable retained references, not an applied
installation or enablement state. Import does not install or enable those
plugins or skills. The live App Server catalog remains authoritative; the
user must review/apply the retained selections separately, and connector
authentication is never restored.

## Android-managed backups

Android cloud backup, device transfer and the legacy full-backup route exclude
the complete private `hans-user-profile-v1.json` document and its `.bak`/`.new`
AtomicFile sidecars. That document can contain unconfirmed interview answers,
a proposed summary and a confirmation nonce. The exclusion does not delete or
relocate the on-device profile during an APK update. Only its confirmed summary
is portable through the user's explicit Hans settings export above; an Android
backup must not silently copy the interview draft instead.
