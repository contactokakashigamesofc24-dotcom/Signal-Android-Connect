# Luiz independent client workstream

This branch is a separate, private working copy based on Signal Android. The original `Signal` prototype repository and its installed APKs are not modified.

## First integration milestone

- Add a `luiz` distribution flavor with package `br.com.contacto.signal.connect`, so test installs can coexist with the current app.
- Keep official Signal's registration and linked-device onboarding active while the server connection is being tested.
- Include the existing welcome/restore UI source as `LuizWelcomePrototypeActivity` in this flavor as a migration reference; it is deliberately not set as launcher because its SMS/registration actions are only local prototypes and would bypass the real Signal client flow.
- Do not describe the app as connected until QR linking and real send/receive tests succeed.

This is an unofficial independent build, not a Signal-maintained client. Review the upstream AGPL-3.0 license and Signal's support policy before distribution.
