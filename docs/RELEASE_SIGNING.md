# Release signing

Android only installs APKs with a valid signature. The release APK produced by a
plain `assembleRelease` is unsigned (`app-release-unsigned.apk`) and will fail to
install with a message such as "There was a problem parsing the package" or
"App not installed". Debug builds are signed automatically with the debug key,
but they cannot share an installation with a release build.

This project reads signing credentials from either:

1. `keystore.properties` in the repository root (local builds), or
2. environment variables (CI builds).

The variables take precedence in this order: `keystore.properties` first, then
the environment.

| `keystore.properties` | Environment variable | GitHub secret |
| --- | --- | --- |
| `storeFile` | `RELEASE_STORE_FILE` | n/a (decoded from `RELEASE_KEYSTORE_BASE64`) |
| `storePassword` | `RELEASE_STORE_PASSWORD` | `RELEASE_STORE_PASSWORD` |
| `keyAlias` | `RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` |
| `keyPassword` | `RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` |

## 1. Generate a keystore

Use the JDK 17 `keytool` (bundled with Android Studio or a JDK install). Run it
once and keep the resulting file safe.

```sh
keytool -genkeypair -v \
  -keystore phone-router-release.jks \
  -alias phone-router \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype JKS
```

- `-alias phone-router` is the key alias. Use the same value for
  `RELEASE_KEY_ALIAS`.
- `-validity 10000` is about 27 years. Android requires the key to remain valid
  for the lifetime of the app; do not use a short validity.
- `keytool` asks for the store password and the key password. They can be the
  same; if you use different values, set both secrets accordingly.

On Windows, `keytool` is in the JDK `bin` directory, for example:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore phone-router-release.jks -alias phone-router `
  -keyalg RSA -keysize 4096 -validity 10000 -storetype JKS
```

Verify the result:

```sh
keytool -list -v -keystore phone-router-release.jks -alias phone-router
```

Back up the keystore **and** its passwords somewhere safe (password manager,
encrypted backup). Losing them means you can never publish an update that
installs over an existing installation.

## 2. Add the secrets to GitHub

The workflow expects four repository secrets. Open
**Settings → Secrets and variables → Actions → New repository secret**.

### `RELEASE_KEYSTORE_BASE64`

Base64-encode the keystore as a single line:

```sh
# Linux
base64 -w0 phone-router-release.jks > keystore.b64

# macOS
base64 -i phone-router-release.jks | tr -d '\n' > keystore.b64
```

```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("phone-router-release.jks")) |
  Set-Content -NoNewline keystore.b64
```

Paste the entire contents of `keystore.b64` as the secret value, then delete the
temporary file.

### `RELEASE_STORE_PASSWORD`

The keystore password entered in step 1.

### `RELEASE_KEY_ALIAS`

The alias from step 1 (`phone-router` in the example).

### `RELEASE_KEY_PASSWORD`

The key password entered in step 1. If `keytool` did not ask for a separate key
password, use the same value as `RELEASE_STORE_PASSWORD`.

### With the GitHub CLI

```sh
gh secret set RELEASE_KEYSTORE_BASE64 < keystore.b64
gh secret set RELEASE_STORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

`gh` prompts for the last three values so they do not end up in shell history.

## 3. How CI uses the secrets

`.github/workflows/build.yml`:

1. Fails a `push`/`workflow_dispatch` build with a clear error if
   `RELEASE_KEYSTORE_BASE64` is missing, so an unsigned release is not published
   by accident. Pull-request builds from forks may still build unsigned.
2. Decodes the base64 secret into `$RUNNER_TEMP/release.keystore` and exports
   `RELEASE_STORE_FILE` for Gradle.
3. Runs `assembleRelease`, which now produces the signed
   `app/build/outputs/apk/release/app-release.apk`.
4. Verifies the APK with `apksigner verify` before uploading the artifact.

## 4. Local signing (optional)

```sh
cp keystore.properties.example keystore.properties
# edit storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleRelease
```

`keystore.properties` and `*.jks`/`*.keystore`/`*.p12` are gitignored. Never
commit the keystore or the generated `keystore.b64`.

## 5. Installing over an existing build

The signature must match the installed app. If a debug-signed build is already
installed, Android refuses to install the release-signed APK over it. Export any
settings you need, uninstall the debug build, then install the signed release.
