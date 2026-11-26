# Standalone messenger migration — development branch

This directory is the isolated backend foundation for the requested email/password messenger. It is separate from the existing Signal-connected builds and does not alter the user's installed Signal app.

## Current backend scope
- Separate D1 database `signal-clone-standalone`.
- Password accounts keyed by email, with PBKDF2-SHA-256 password hashing and opaque bearer sessions.
- Direct conversations found by registered email and text-message endpoints.
- D1 rows are structurally separated from every other app database.

## Not ready for real users
This is a development scaffold, not a production messenger. There is no email ownership verification, no password reset, no rate limiting/abuse controls, no device push, media, groups, or voice/video calls. Most importantly, message bodies are stored as plaintext in the development D1 database: HTTPS protects network traffic, but this backend is **not end-to-end encrypted**. Do not use real credentials or private messages until end-to-end encryption, account verification, recovery, abuse protection, and the Android client integration are implemented and tested.

The Android clone still uses the Signal registration and messaging stack until that client is migrated. This backend does not connect to Signal and does not make Signal accounts available to it.
