# JARVIS for Android (wake word, works with the screen off)

An Android app with a **foreground service** that listens for "Jarvis" or "Hey Jarvis" using
**Vosk** (free, offline, **no account, no key, no company email**). Then it hears one command, answers
out loud, and goes back to listening. Made for a Vivo T4 5G (Android 15, Funtouch OS).

**Be aware before you start**
- I could not compile or run this here (no Android build tools in my workspace). It is a first version and
  untested. If the build shows a red error, send me the exact text and I will fix it.
- The wake word is found by a small speech model, not a trained wake-word engine. It reacts to the word
  **"Jarvis"** (so "Hey Jarvis" works too). It can miss you or react to similar sounds, and it uses more battery
  than a dedicated engine.

---

## Fastest way: get a ready-to-install APK with no Android Studio
GitHub builds the APK for you, free, in about 5 to 8 minutes. You can do this from a computer
(a phone browser set to "Desktop site" also works).

1. Sign in or sign up at https://github.com and click **New repository**. Name it `jarvis`. Choose **Private**. Create it.
2. Click **uploading an existing file**. Drag in everything from inside the unzipped `JarvisAssistant` folder
   (the `app` folder, the `gradle` folder, and the loose `.kts` and `.properties` files). Click **Commit changes**.
3. Click **Add file > Create new file**. In the name box type exactly `.github/workflows/build-apk.yml`
   (typing the slashes makes the folders). Paste the full contents of `build-apk.yml` (sent with this project),
   then click **Commit changes**.
4. Open the **Actions** tab. Wait for "Build Jarvis APK" to get a green tick. If it shows a red cross, open it and
   send me the error text.
5. Open the finished run, scroll to **Artifacts**, download **jarvis-apk**, and unzip it to get `app-debug.apk`.
6. Copy `app-debug.apk` to the phone, open it, and allow "install unknown apps".
   The APK is about 60 MB because it carries the speech model.

## First run on the phone
1. Open **Jarvis** and tap **Start Jarvis**. Allow **Microphone** and **Notifications**.
2. The first start says "Preparing speech model". Wait about a minute. This happens only once.
3. A notification "Say Jarvis or Hey Jarvis" appears. It must stay. That is the foreground service.
4. Tap **Allow background battery use** and accept.
5. Do the Vivo settings below. They decide whether it keeps working with the screen off.

## Vivo settings (needed so the phone does not kill it)
Menu names change between Funtouch OS versions. Look for the closest match.
1. **Battery:** Settings > Battery > **Background power consumption management** (or High background power
   consumption) > **Jarvis** > **Allow**.
2. **Autostart:** Settings > Apps > **Autostart** (or iManager > App manager > Autostart) > turn **Jarvis** on.
3. **Lock in recents:** open the recent apps screen, pull down on the Jarvis card or tap its menu, and choose **Lock**.
4. **Notifications:** Settings > Notifications > Jarvis > allow all, including **Lock screen**.
5. **Battery saver:** turn off Battery saver / Super power saving while testing.
6. **Other voice assistants:** if Jovi or "Hey Google" listening takes the microphone, turn it off while testing.

## Test
1. With the notification showing, lock the phone.
2. Say **"Hey Jarvis"** clearly from close by. You hear a short beep.
3. Say a command, for example:
   - "what time is it", "what is the date", "battery level"
   - "flashlight on", "flashlight off"
   - "volume up", "volume down"
   - "set a timer for 5 minutes"
   - "tell me a joke", "tell me a story"
   - "play kids stories on YouTube", "search for rabbit drawings", "open Gmail"
   - "goodbye Jarvis" (stops the service)
4. Jarvis answers out loud and returns to waiting.

## Build it yourself in Android Studio instead (optional)
1. Install Android Studio and open the `JarvisAssistant` folder. Let Gradle sync.
2. **Speech model:** download `vosk-model-small-en-us-0.15.zip` from https://alphacephei.com/vosk/models,
   unzip it, rename the folder to `model-en-us`, and put it inside `app/src/main/assets/`.
3. Press **Run** with your phone connected (USB debugging on), or use **Build > Build APK(s)**.

## How it works
| File | Job |
|---|---|
| `JarvisService.kt` | Foreground service of type microphone. Runs Vosk for the wake word, then Android's speech recognizer for one command, then text-to-speech, one at a time. Holds a partial wake lock so the CPU keeps running with the screen off. |
| `CommandHandler.kt` | Maps what you said to an action and a reply. Add your own commands here. |
| `MainActivity.kt` | Screen to start or stop, and open battery settings. |
| `AndroidManifest.xml` | Permissions and the `microphone` foreground service type. |

## Honest limits
- **Opening apps and web pages:** Android stops background services from opening apps directly. Jarvis posts a
  notification. Tap it to open.
- **Restart:** Android 15 does not let a microphone service start by itself after a reboot. After restarting the phone,
  open the app and tap **Start Jarvis** once.
- **Command understanding** uses Android's built-in speech recognition (the Google app). It may need internet.
- **Microphone use:** Android shows the green microphone dot while Jarvis listens. If a call, voice recorder or another
  app takes the microphone, Jarvis pauses.
- **Battery:** this listens continuously with a speech model. Expect a clear battery drain.
- **Not smart:** replies come from a fixed command list. It is not a chat AI.
- **Vivo may still stop it.** If it dies after some time, repeat the Vivo settings.

## Troubleshooting
- **Notification says "Speech model missing":** the model folder was not in the build. Use the GitHub steps above,
  or follow "Speech model" in the Android Studio section.
- **It never reacts to "Jarvis":** speak clearly and close to the phone. For Indian-accent speech you can try
  `vosk-model-small-en-in-0.4` from the same models page: put it in `assets` as `model-en-us`.
- **It reacts by itself:** change the pattern in `JarvisService.kt` (`wakePattern`) to be stricter, for example only
  `jarvis`.
- **No spoken reply:** Settings > Language > Text-to-speech output, and install a Google speech voice.
- **Build error:** copy the exact red message and send it to me.
