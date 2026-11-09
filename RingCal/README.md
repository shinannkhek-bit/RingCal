# RingCal – calendar reminders that ring until you snooze

A small Android calendar app whose reminders behave like alarm clocks: the sound loops
and the phone vibrates until you tap **Snooze** (5 min) or **Dismiss**. Works on the
lock screen. Events can be imported from and exported to `.ics` files.

## Build and install (needs a computer, about 20–30 minutes the first time)

1. Install **Android Studio** (free) from developer.android.com/studio.
2. Unzip this folder, then in Android Studio choose **File > Open** and select the `RingCal` folder.
   Let it finish "Gradle sync" (it downloads what it needs).
   - If sync complains about the Gradle wrapper, accept Android Studio's offer to fix/create it.
   - Fallback: create a new project (template **No Activity**, language Kotlin, package
     `com.example.ringcal`, minimum SDK API 26), then replace its `app/src/main` folder and
     `app/build.gradle.kts` with the ones from this zip.
3. On the Honor phone, turn on developer mode: **Settings > About phone**, tap **Build number**
   7 times. Then in **Settings > System & updates > Developer options**, turn on **USB debugging**.
4. Connect the phone by USB, pick it in the device list at the top of Android Studio, and press ▶ Run.
   (Or use **Build > Build APK(s)**, copy `app-debug.apk` to the phone, and open it to install.)

## First-time setup on the phone

1. Open RingCal and tap **Fix this** repeatedly until it says "Alarm setup OK"
   (notifications, exact alarms, full-screen alarms, battery optimisation).
2. Honor-specific (important, or MagicOS may kill the app): open **Settings**, search
   **App launch**, find RingCal, switch it to **Manage manually**, and turn on
   Auto-launch, Secondary launch and Run in background.
3. Test: add an event, tap it, choose **Test ring in 5 seconds**, and lock the phone.

## Import / export

- **Import .ics**: pick any `.ics` file (e.g. exported from Google Calendar on the web via
  Settings > Import & export, which gives a zip of `.ics` files). You can also open an `.ics`
  file from your file manager with RingCal. Events already imported (same UID) are skipped.
- **Export .ics**: saves all RingCal events with their reminders to one `.ics` file you can
  import into another calendar.

## Good to know

- Snooze length: change `SNOOZE_MINUTES` in `RingService.kt`.
- Events without a reminder in the file get a 10-minute reminder (`DEFAULT_REMIND` in `Ics.kt`).
- All-day events are treated as starting at 09:00 for ringing purposes.
- Repeating events: only the first occurrence is imported (RRULE is ignored).
- An alarm-clock icon appears in the status bar when an alarm is pending; that is what lets
  Android fire it exactly on time.
