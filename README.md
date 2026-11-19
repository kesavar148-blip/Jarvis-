# JARVIS for Android (AI agent, wake word, works with the screen off)

An Android app with a **foreground service** that listens for "Jarvis" or "Hey Jarvis" using
**Vosk** (free, offline, **no account, no key, no company email**). Then it hears one command, answers
out loud, and goes back to listening. Made for a Vivo T4 5G (Android 15, Funtouch OS).

**Be aware before you start**
- **What has and has not been tested.** The alarm time parser (understanding "tomorrow at 8 AM", "every weekday",
  "in 30 minutes" ...) has 28 automated checks, and all pass (run in three time zones). Everything that touches Android
  (ringing, the lock-screen screen, voice, the wake word, the AI) has **never run on a phone**: my workspace has no
  Android SDK and no device. Expect some bugs. If the build shows a red error, or something misbehaves, send me the
  exact text or what you saw, and I will fix it.
- The wake word is found by a small speech model, not a trained wake-word engine. It reacts to the word
  **"Jarvis"** (so "Hey Jarvis" works too). It can miss you or react to similar sounds, and it uses more battery
  than a dedicated engine.

---

## NEW: alarms, wake-up, reminders, timers and to-do list
These work **without a Gemini key and without internet**. Each one is saved in a small database on the phone and handed to
Android's AlarmManager, so it rings at the exact time with the screen off, even if Jarvis itself is not running.

Say (after "Hey Jarvis"):
- "Wake me up tomorrow at 8 AM". Jarvis repeats the time. At 8 AM you get a loud alarm, vibration, Jarvis saying
  "Good morning! It's 8 AM. Time to wake up.", and a lock-screen screen with **Snooze** and **Dismiss**.
- "Remind me to study at 6 PM", "remind me in 30 minutes to call mom", "wake me up at 5 AM every day",
  "wake me up every weekday at 6 AM", "remind me every Monday at 10 AM to submit the report"
- "Call me tomorrow morning at 7 AM". Jarvis sets an **alarm** and says plainly that it is not a real phone call.
- "What alarms do I have", "cancel the 8 AM alarm" (it cancels one at a time, never "all"), "set a timer for 10 minutes"
- "Add a task buy milk", "what tasks are pending", "complete task buy milk"

How it behaves:
- If you do not say AM or PM, it chooses (morning for "wake me up", the soonest future time otherwise) and tells you
  the time it chose, so you can correct it.
- An alarm nobody answers stops after 3 minutes, snoozes itself, and after 3 rounds is reported as missed with a
  notification ("Sir, you missed your alarm"). Snooze length is 5 minutes; change it on the Reminders screen.
- After a restart, alarms are put back automatically. Any whose time passed while the phone was off are reported as
  missed (one that was due in the last 2 minutes just rings). Opening Jarvis also puts everything back.
- Open the app and tap **Reminders and alarms** to see, cancel or add them, set the snooze length, and run
  **Test alarm in 1 minute**.

Android notes:
- Alarms use `AlarmManager.setAlarmClock()`. It is exact, works in Doze, and does **not** need the "Alarms & reminders"
  permission (Android 14 turns that off by default for apps installed outside the Play Store). Side effect: Android
  shows a small alarm icon in the status bar while one is scheduled.
- Android 14 and newer may block the full-screen alarm screen until you allow "full-screen notifications" for Jarvis.
  The Reminders screen shows a button for it. Without it the alarm still rings and speaks, with a notification
  that has Snooze and Dismiss.
- **Force stop** (Settings > Apps > Jarvis > Force stop) makes Android delete all of an app's alarms. Open Jarvis once
  to restore them.
- On Vivo, turn **Autostart** and background power use on (see Vivo settings), or restart recovery can be blocked.
- **Real phone-call wake-ups are not built.** They need a paid cloud phone service (for example Twilio: a phone number
  plus a charge per call, and a small server or Cloudflare Worker holding the secret key). The local alarm is the reliable
  part, and Jarvis never says a call was made when only an alarm rang.

---

## NEW: the AI brain (Gemini, free tier)
Jarvis can now understand free speech, answer questions, chat, and choose phone actions by itself.
It uses Google's Gemini API. Your spoken command is sent to Google as text, so it needs internet.

1. Get a free key: open https://aistudio.google.com/apikey, sign in with a Google account, tap **Create API key**.
2. Open the Jarvis app, paste the key in the **Gemini API key** box, tap **Save key**.
3. The model box already holds `gemini-3.1-flash-lite`, the model Google lists as free. If Google renames it, type the
   new free model name there.
4. Tap **Start Jarvis**. Say "Hey Jarvis", then anything, for example:
   - "Wake me up at 6 30 tomorrow", "remind me in 20 minutes to drink water"
   - "Navigate to Tirupati railway station", "play Telugu kids songs on YouTube"
   - "Send a WhatsApp to 91XXXXXXXXXX saying I will be late"
   - "Explain why the sky is blue", "tell me a story about a clever crow"
5. If Jarvis asks you a question, just answer. It listens again without the wake word.

Tools Gemini can use: flashlight, volume, timer, battery, alarms and reminders (schedule, list, cancel), to-do list,
web search, YouTube, Maps directions, dialer, WhatsApp message, open a link. Actions that open another app still show a notification you tap (Android rule).
Calls and WhatsApp messages are only prepared; you press call or send.

With no key, or with no internet, Jarvis falls back to the old fixed offline commands.

Limits of the free tier: Google may slow or stop requests when you talk a lot ("I have used up the free Gemini limit").
Free-tier data may be used by Google to improve its products, so do not say private things to it.
Live facts (weather, news) are not known by the model, so it opens a Google search instead.

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

## Test the alarms (do these in order)
1. Open the app, tap **Reminders and alarms**, allow notifications, then tap **Test alarm in 1 minute**. Lock the phone and
   wait. Expect: loud alarm, vibration, a spoken line, and the Snooze/Dismiss screen over the lock screen.
2. Tap **Snooze**: it stops and rings again after the snooze length. Tap **Dismiss**: it stops for good.
3. Run another test alarm and do nothing. Expect it to stop after 3 minutes and ring again after the snooze time.
4. Say "Hey Jarvis, wake me up in 2 minutes" with the screen locked. Expect Jarvis to say the time, and the alarm to ring.
5. Say "Hey Jarvis, wake me up tomorrow at 8 AM", then open Reminders and alarms and check the entry. Say "Hey Jarvis,
   what alarms do I have", then "Hey Jarvis, cancel the 8 AM alarm".
6. Restart test: schedule an alarm 10 minutes ahead, restart the phone, wait. It should ring (Autostart must be on).
7. On a computer, `gradle :app:testDebugUnitTest` runs the 28 time-parser checks.

## Build it yourself in Android Studio instead (optional)
1. Install Android Studio and open the `JarvisAssistant` folder. Let Gradle sync.
2. **Speech model:** download `vosk-model-small-en-us-0.15.zip` from https://alphacephei.com/vosk/models,
   unzip it, rename the folder to `model-en-us`, and put it inside `app/src/main/assets/`.
3. Press **Run** with your phone connected (USB debugging on), or use **Build > Build APK(s)**.

## How it works
| File | Job |
|---|---|
| `JarvisService.kt` | Foreground service of type microphone. Runs Vosk for the wake word, then Android's speech recognizer for one command, then text-to-speech, one at a time. Holds a partial wake lock so the CPU keeps running with the screen off. |
| `GeminiAgent.kt` | The AI brain. Sends your words to Gemini with a list of phone tools, runs the tools Gemini picks, returns one short sentence to speak. |
| `CommandHandler.kt` | The phone actions (torch, volume, links), the offline command list, and the offline alarm, reminder, timer and task commands. Add your own tools here and in `GeminiAgent.kt`. |
| `MainActivity.kt` | Screen to start or stop, and open battery settings. |
| `TimeParser.kt` | Understands "tomorrow at 8 AM", "every weekday", "in 30 minutes". Plain Kotlin, unit tested. Also repeat rules and spoken times. |
| `Reminder.kt`, `ReminderDb.kt` | The alarm, reminder, timer and task records, saved in SQLite on the phone. |
| `Reminders.kt` | Create, list and cancel alarms, reminders, timers and tasks. Used by voice, the AI and the screens. |
| `AlarmScheduler.kt` | Hands alarms to AlarmManager and puts them back after a restart. |
| `AlarmReceiver.kt`, `AlarmService.kt`, `AlarmActivity.kt` | Android wakes the receiver at the alarm time. The service plays the sound, vibrates and speaks. The activity is the lock-screen Snooze/Dismiss screen. |
| `BootReceiver.kt` | Restores alarms after restart, app update, or a clock change. |
| `RemindersActivity.kt` | Dashboard: list, cancel, new alarm, test alarm, snooze length, permission status. |
| `Notifs.kt` | Notification channels and the "you missed an alarm" notification. |
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
- **Smart only with a Gemini key and internet.** Without them it uses the fixed command list.
- **Vivo may still stop it.** If it dies after some time, repeat the Vivo settings.

## Not built yet (planned, in this order)
Gmail and Google Calendar (these need a Google Cloud OAuth client that only you can create), reading today's schedule,
proactive alerts for upcoming events and important emails, the futuristic Compose interface, switching between AI
providers, encrypted token storage, and a better wake-word engine. Nothing in this list works yet.

## Troubleshooting
- **An alarm did not ring:** check Reminders and alarms for the entry and its status. Then check Vivo Autostart,
  background power use, and that you did not Force stop the app. Tell me what the screen showed.
- **Notification says "Speech model missing":** the model folder was not in the build. Use the GitHub steps above,
  or follow "Speech model" in the Android Studio section.
- **It never reacts to "Jarvis":** speak clearly and close to the phone. For Indian-accent speech you can try
  `vosk-model-small-en-in-0.4` from the same models page: put it in `assets` as `model-en-us`.
- **It reacts by itself:** change the pattern in `JarvisService.kt` (`wakePattern`) to be stricter, for example only
  `jarvis`.
- **No spoken reply:** Settings > Language > Text-to-speech output, and install a Google speech voice.
- **Build error:** copy the exact red message and send it to me.
