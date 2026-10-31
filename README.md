# Stringalize for Android

**Fix a translation in your app today, not in the next release.**

This library adds live updates to your Android app. Publish a release in [Stringalize](https://stringalize.app) and installed apps use the new text from their next launch: no new build, no Play Store review.

Your strings stay where they are, in `res/values*/strings.xml`, and your code keeps calling `getString`, `getQuantityString` or Compose's `stringResource`.

- [Requirements](#requirements)
- [Installation](#installation)
- [Getting started](#getting-started)
- [How it works](#how-it-works)
- [What gets updated](#what-gets-updated)
- [API](#api)
- [Testing a release](#testing-a-release)
- [Privacy](#privacy)
- [FAQ](#faq)

## Requirements

- Android 7.0 (API 24) or later
- A project in [Stringalize](https://stringalize.app) holding your app's strings, with the same names your `strings.xml` uses. The easiest way to get there is to import your `strings.xml` files.

## Installation

The library is published through [JitPack](https://jitpack.io/#cbarbera80/stringalize-android).

In `settings.gradle.kts`, add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.cbarbera80:stringalize-android:0.1.0")
}
```

## Getting started

### 1. Copy your token

In Stringalize, open the project and choose **Live updates**. Copy the token: it starts with `ota_`.

The token only downloads published releases. It cannot read anything else in the project or change it, so it is safe to ship inside the app.

### 2. Start the library in your Application

```kotlin
import app.stringalize.Stringalize

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Stringalize.start(this, token = "ota_…")
    }
}
```

If you don't have an `Application` class yet, create one and register it in `AndroidManifest.xml` with `android:name=".MyApplication"`.

### 3. Wrap your activities

Every Activity that shows text needs one override. The simplest way is a base class your activities share:

```kotlin
import app.stringalize.Stringalize

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Stringalize.wrap(base))
    }
}
```

For a Compose app with a single `ComponentActivity`, add the same override to it.

### 4. Publish a release

Edit a translation in Stringalize, mark it translated, then press **Publish release** in the Live updates dialog. Apps download the release the next time they start, and show it from the launch after that.

## How it works

```
 Stringalize                         Your app
 ───────────                         ────────
 Publish release 4  ──────────────▶  Launch N:   shows its current text,
                                                 downloads release 4 in the background
                                     Launch N+1: shows release 4
```

- **A release is a snapshot.** Only translations marked *translated* are in it. Drafts, fuzzy and flagged translations stay out, and for those strings the app keeps the text it shipped with. A published release never changes: to change something, publish a new one.
- **Text never changes under the user's eyes.** A new release is used from the next launch. If you prefer, `applyImmediately = true` uses it as soon as it arrives, for text set from then on.
- **Only the user's language is downloaded.** The library picks it from the device's languages (or the app's own language setting), among the ones published. When none matches, it uses the source language, which is what Android shows from `res/values`.
- **Nothing breaks when the network does.** Offline, on a slow connection or if the server can't be reached, the app keeps what it has. The library never throws and never blocks launch. Checks are cheap: when nothing new is published, the server answers with an empty "not modified".
- **Plurals follow the user's language.** Releases carry every plural form, and the library picks the right one with Android's plural rules, for example four forms in Polish.

## What gets updated

| | |
|---|---|
| `getString`, `getText`, `getQuantityString` on a wrapped Activity | ✅ |
| Compose `stringResource` and `pluralStringResource` | ✅ |
| `android:text` and `android:hint` of TextViews, Buttons and EditTexts in XML layouts | ✅ |
| View Binding and Data Binding layouts | ✅ |
| Fragments inside a wrapped Activity | ✅ |
| Strings with styling markup (`<b>`, `<i>`, `<xliff:g>`) | ❌ keep the bundled text |
| Menus, toolbar titles set in XML, preferences XML, the app name and other manifest labels | ❌ |
| Custom views declared in layouts by their full class name, in apps without AppCompat or Material | ❌ |
| `getString` on the Application context | ✅ if you also wrap it, see the FAQ |

Strings are matched by resource name. A key with dots in Stringalize, such as `home.title`, also answers for `R.string.home_title`.

## API

```kotlin
Stringalize.start(
    context: Context,
    token: String,
    applyImmediately: Boolean = false,   // use a new release as soon as it is downloaded
    server: String = Stringalize.DEFAULT_SERVER,
)
```

Call it once, in `Application.onCreate`. Later calls are ignored.

```kotlin
Stringalize.wrap(base: Context): Context
```

Returns a context whose resources answer with released strings. Use it in `attachBaseContext`.

```kotlin
Stringalize.release: Int?
```

The number of the release in use, or `null` while the app shows only its bundled strings. Handy in a debug or about screen.

```kotlin
Stringalize.checkForUpdates { downloaded -> … }
```

Checks for a newer release now, for example when the app returns to the foreground. The callback runs on the main thread, with `true` when one was downloaded.

```kotlin
Stringalize.addListener { release -> … }
```

Called on the main thread when a release has been downloaded. Remove it with `removeListener`.

From Java the same methods are static: `Stringalize.start(this, "ota_…")`.

## Testing a release

1. Run the app once, so it downloads the latest release.
2. Close it from the recent apps and open it again: the new text is there.
3. To check which release is in use, show `Stringalize.release` somewhere in a debug build.

The library logs what it does with the tag `Stringalize`, visible in Logcat.

To start over, clear the app's storage, or uninstall it.

## Privacy

Each check sends the token, the release number and the language code. The library sends nothing about the device or the person using it, uses no identifiers, and stores only the downloaded translations, in the app's private files.

How Stringalize handles these requests is described in its [privacy notice](https://stringalize.app/privacy/).

## FAQ

**Is this allowed on Google Play?**
The library downloads text, the same kind of content an app shows from any server. It downloads no code, and it uses only public Android APIs.

**What happens with strings my app doesn't have yet?**
They are downloaded but never looked up, so they have no effect. Strings removed from Stringalize simply fall back to the text in the app.

**Can I update strings read from the Application context?**
Yes. Wrap it too, in your `Application` class:

```kotlin
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(Stringalize.wrap(base))
}
```

**Does it work with per-app languages?**
Yes. The library follows the language the app's resources are in, including one set with `AppCompatDelegate.setApplicationLocales`.

**Can I use it with my own Stringalize server?**
Pass its address as `server`.

**iOS?**
See [stringalize-ios](https://github.com/cbarbera80/stringalize-ios).

## Example app

[`example/`](example) holds a small app that shows each kind of string: from code, from an XML layout, from Compose and a plural. Open the folder in Android Studio, add your token to `local.properties`, and run it on an emulator:

```properties
stringalize.token=ota_…
# Optional; the default is a Stringalize server running on the Mac, seen from the emulator.
stringalize.server=https://stringalize.app
```

## License

MIT. See [LICENSE](LICENSE).
