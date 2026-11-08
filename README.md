# GMSingular (Android)

Lightweight wrapper to initialize the Singular SDK, manage a Going Merry app user, and persist identifiers on device.
This is the Android counterpart of the iOS [GMSingular](https://github.com/goingmerry/GMSingular) package and follows the same flow.

- Singular Android SDK: `com.singular.sdk:singular_sdk:12.17.0`
- minSdk 21, Kotlin, coroutines

## Implementation Steps

Follow these steps in order.

### Step 1: Start the Singular session

Call `startSingularSession` in your launcher Activity's `onCreate`, before `setContent`.
Android has no App Tracking Transparency prompt, so this both configures **and starts** Singular, then fires an `app_open` event.
It belongs in the Activity (not `Application.onCreate`) because Singular reads the launch `Intent` for deep links.

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GMSingularClient.shared(this).startSingularSession(
            singularApiKey = BuildConfig.SINGULAR_SDK_KEY,
            singularSecret = BuildConfig.SINGULAR_SDK_SECRET,
            intent = intent,
            customerUserId = currentUserId,
            enableLogging = BuildConfig.DEBUG,
        )

        setContent { App() }
    }
}
```

### Step 2: Initialize the App User

After Singular has started, call `initialize()` from a coroutine to register or fetch the app user from the Going Merry backend.

```kotlin
lifecycleScope.launch {
    runCatching {
        GMSingularClient.shared(this@MainActivity).initialize(
            applicationId = BuildConfig.GM_APPLICATION_ID,
            customUserId = currentUserId,
            adaptyId = currentUserId,
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            appStoreCountry = Locale.getDefault().country,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}
```

`singularDeviceId` is resolved automatically when you leave it `null`:

1. the value you pass in,
2. the Singular device ID of the stored app user,
3. the Singular device ID reported by the SDK (saved on device once received),
4. otherwise it waits up to `singularDeviceIdTimeoutMillis` (default 10 s) for the SDK to report it, then throws `GMSingularException.MissingSingularDeviceId`.

> **Important:** On Android the Singular device ID is only delivered asynchronously by the SDK after `startSingularSession`. There is no synchronous `Singular.singularID()` like on iOS.

### Important: User ID Consistency

The `customUserId` passed to Singular and the Adapty customer user ID (`adaptyId`) **must be the same value** so Going Merry can correlate Singular attribution with Adapty subscription events.

```kotlin
val userId = "user_12345"

GMSingularClient.shared(this).startSingularSession(
    singularApiKey = "...",
    singularSecret = "...",
    intent = intent,
    customerUserId = userId,
)

GMSingularClient.shared(this).initialize(
    applicationId = "...",
    customUserId = userId,
    adaptyId = userId,
    deviceId = "...",
)
```

### Deep links (optional)

Pass a `singularLinksHandler` (and your `brandedDomains`, e.g. `xxx.sng.link`) to receive Singular links and deferred deep links, for example web-to-app payloads delivered through `passthrough`.
Forward later intents from `onNewIntent` so links opened while the app is running are resolved too.

```kotlin
GMSingularClient.shared(this).startSingularSession(
    singularApiKey = "...",
    singularSecret = "...",
    intent = intent,
    customerUserId = userId,
    brandedDomains = listOf("xxx.sng.link"),
    singularLinksHandler = { params ->
        val deeplink = params.deeplink
        val passthrough = params.passthrough
        val isDeferred = params.isDeferred
    },
)

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    GMSingularClient.shared(this).handleNewIntent(intent)
}
```

Singular links also need an App Links `<intent-filter android:autoVerify="true">` for your branded domain on the Activity.

### Attribution forwarding (optional)

`deviceAttributionCallback` delivers Singular's attribution payload so you can forward it, for example to Adapty:

```kotlin
deviceAttributionCallback = { attribution ->
    Adapty.updateAttribution(attribution, "singular") { error -> }
}
```

Singular invokes the handlers on its own threads; switch to the main thread before touching UI.

### Summary: Correct Initialization Order

1. **`startSingularSession(...)`** — configure and start Singular (launcher Activity `onCreate`)
2. **`initialize(...)`** — register the app user with the Going Merry backend
3. **`handleNewIntent(...)`** — from `onNewIntent`, only if you use Singular links

## Installation

The Singular SDK is hosted on Singular's own Maven repository, so every consuming app must add it:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.singular.net/")
    }
}
```

### Option A: JitPack (after pushing this repo to GitHub)

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```
```kotlin
implementation("com.github.goingmerry:GMSingular-Android:<tag>")
```

### Option B: Local checkout (composite build)

In the app's `settings.gradle.kts`:
```kotlin
includeBuild("../GMSingular-Android")
```
```kotlin
implementation("com.goingmerry:gmsingular:0.1.0")
```

### Option C: Maven local

```
./gradlew :gmsingular:publishToMavenLocal
```
Then add `mavenLocal()` to the app's repositories and depend on `com.goingmerry:gmsingular:0.1.0`.

The library merges the `INTERNET`, `ACCESS_NETWORK_STATE` and `com.google.android.gms.permission.AD_ID` permissions and ships R8 keep rules for Singular, so no extra manifest or ProGuard setup is needed in the app.

## API Reference

### `GMSingularClient`

| Method | Description |
|--------|-------------|
| `shared(context)` | Process-wide instance (equivalent of `GMSingularClient.shared` on iOS) |
| `startSingularSession(...)` | Builds the config, calls `Singular.init`, fires `app_open`. Returns `false` if keys are blank or init fails |
| `initialize(...)` | Registers or loads the app user, then applies its `customUserId` to Singular |
| `handleNewIntent(intent)` | Re-runs Singular with a new deep-link intent |
| `makeSingularConfig(...)` | Returns a configured `SingularConfig` if you want to call `Singular.init` yourself |
| `waitForSingularDeviceId(timeoutMillis)` | Suspends until the SDK reports the Singular device ID |
| `currentSingularDeviceId()` | Singular device ID reported by the SDK, or `null` |
| `savedCustomUserId()` | Stored custom user ID |
| `savedSingularDeviceId()` | Stored Singular device ID |
| `savedAdaptyId()` | Stored Adapty ID |
| `currentAppUser()` | Current `AppUserResponse`, if any |
| `clearStoredAppUser()` | Wipes the cached and stored app user |

### `initialize` parameters

| Parameter | Description |
|-----------|-------------|
| `applicationId` | Your Going Merry app ID (sent as `Authorization: Bearer <id>`) |
| `customUserId` | Your app's user identifier (should match `adaptyId`) |
| `adaptyId` | The Adapty customer user ID (should match `customUserId`) |
| `deviceId` | Device identifier (typically `Settings.Secure.ANDROID_ID`) |
| `singularDeviceId` | Singular device ID (auto-resolved when `null`) |
| `appStoreCountry` | Store country code |
| `appVersion` | App version name |
| `androidVersion` | OS version (defaults to `Build.VERSION.RELEASE`), sent as `android_version` |
| `forceRefresh` | `true` bypasses the stored user and calls the backend again |
| `singularDeviceIdTimeoutMillis` | How long to wait for the SDK to report the Singular device ID |

### Errors

`initialize` throws `GMSingularException`: `MissingRequestData`, `MissingSingularDeviceId`, `InvalidStatusCode(statusCode)`, `EncodingFailed`, `DecodingFailed`, `Storage`. Network failures surface as `IOException`.

## Differences from iOS

| iOS | Android |
|-----|---------|
| `startSingularSession` only prepares the config; `requestTrackingAuthorizationAndStartSingularSession()` starts it after ATT | No ATT on Android: `startSingularSession` starts Singular immediately |
| `Singular.singularID()` polled for up to 3 s | Singular device ID arrives through `SDIDAccessorHandler`, is saved on device, and `initialize` waits up to 10 s |
| `userActivity` for universal links | Launch `Intent` plus `handleNewIntent` for App Links |
| SKAdNetwork, conversion values, clipboard attribution | Not applicable on Android (attribution uses the Play Install Referrer) |
| Keychain storage (survives reinstall) | SharedPreferences (cleared on uninstall unless restored by Android backup) |
| `iOS_version` request field | `android_version` request field |

## Notes

- Keys are **not** hard-coded; always pass your Singular SDK key and secret from the app.
- `initialize` returns the stored app user without calling the backend until `forceRefresh = true` or `clearStoredAppUser()` is called, the same as on iOS.
- If your app enables Android backup, consider excluding the `com.goingmerry.gmsingular` shared preferences file so a restored device does not reuse another device's Singular device ID.
