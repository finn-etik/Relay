# Signing

Android installs an update over an app only if both were signed with the
same key. All nine apps are signed in CI with one permanent key, the
"Field kit" key, so every build installs over the previous one.

- Fingerprint (SHA-256):
  `FC:4D:BD:27:E0:FB:C5:B6:36:12:36:C0:3A:29:35:81:D2:43:4D:F1:1D:C3:51:2F:6D:A1:19:1A:33:13:AA:6D`
- Valid until November 2056. Alias `field`; the key's password is the
  keystore's.

The key is **not in this repository**. CI reads it from two repository
secrets (Settings, Secrets and variables, Actions):

| Secret | Value |
|--------|-------|
| `FIELD_KEYSTORE_B64` | the keystore file (`.jks`), base64 on one line |
| `FIELD_KEYSTORE_PASSWORD` | its password |

Without them a run still builds, warns, and signs with a throwaway debug
key (those APKs do not install over earlier ones). The "Collect" step of a
run prints the fingerprint of the key it used.

GitHub never shows a secret again once saved: keep a copy of the keystore
and its password somewhere safe. If the key is lost, a new one works, but
every app must be uninstalled once before the next install.

A local `./gradlew assembleRelease` signs with the machine's debug key
unless `FIELD_KEYSTORE_PATH` and `FIELD_KEYSTORE_PASSWORD` are set in the
environment.
