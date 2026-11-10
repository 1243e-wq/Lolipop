# 📹 Pocket CCTV

Turn two spare Android devices into a home security system.

| Device | Role |
|---|---|
| Android 8 phone | **Camera**: records, backs up to Google Drive, streams live video |
| Android 11/12 tablet | **Monitor**: live view and playback of recordings |

One app does both. Install the **same APK** on both devices.

## What it does

- 480p, 30 fps video, recorded in **continuous 2-hour clips**
- **Night vision ON by default**: black-and-white recording, shown with a green look on the monitor
- When a clip finishes it is **uploaded to Google Drive automatically**
- After the upload the app **checks that Google holds an identical copy** (same size and MD5 checksum). **Only then** is the clip deleted from the camera phone. If the check fails, the clip stays and the upload is retried.
- **3-day retention**: Drive clips older than 3 days are deleted automatically (adjustable 1–14 days in the app)
- Uploads resume after a dropped connection instead of starting over
- Live view and playback on the tablet; friendly, big-button screens
- PIN protection for live view

---

## Build online (no Android Studio needed)

1. Create a free account at <https://github.com> and make a **new private repository**.
2. Click *Add file → Upload files* and drag in **everything inside the `PocketCCTV` folder** (include the hidden `.github` folder; if the web uploader skips it, create the file `.github/workflows/build-apk.yml` by pasting its contents).
3. Open the **Actions** tab → *Build APK* → **Run workflow**. After about 5 minutes open the finished run and download **PocketCCTV-apk** (a zip containing `app-debug.apk`).
4. Install that APK on both devices.

The project is signed with a fixed key, so your Google registration (step 2 below) uses this SHA-1 and stays valid for every build:

`AB:3D:04:E3:BB:47:F0:E1:F5:9B:16:A0:36:18:2F:01:B3:E3:78:1D`

(Keep the repository private. The signing key is included for your personal use.)

## Step 1: Build the app (alternative: on a computer) (about 5 minutes)

1. Install **Android Studio** (free) on a computer.
2. *File → Open* and choose this `PocketCCTV` folder. Wait for "Gradle sync" to finish (needs internet the first time).
   - If Studio asks about a Gradle wrapper or an AGP upgrade, accept the suggested option.
3. *Build → Build Bundle(s) / APK(s) → Build APK(s)*. Studio shows a **locate** link to `app-debug.apk`.
4. Copy that APK to **both** the phone and the tablet and install it (allow "install unknown apps" if asked).

## Step 2: One-time Google setup (about 10 minutes)

Google only lets an app use Drive after you register it. This is free, and your files stay in your own Drive.

1. Go to <https://console.cloud.google.com> and create a **new project** (any name).
2. *APIs & Services → Library* → search **Google Drive API** → **Enable**.
3. *APIs & Services → OAuth consent screen*
   - User type: **External** → fill in an app name and your email.
   - *Scopes* → add `.../auth/drive.file`
   - *Test users* → add your own Gmail address.
   - When finished, press **Publish app**. This avoids sign-ins expiring after 7 days. The `drive.file` permission needs no Google review.
4. Find your app's **SHA-1 fingerprint**. It is the fixed value shown above. (Or in Android Studio open the **Gradle** panel (right side) → *app → Tasks → android → signingReport* and copy the `SHA1` value of the **debug** variant.)
5. *APIs & Services → Credentials → Create credentials → OAuth client ID*
   - Application type: **Android**
   - Package name: `com.pocketcctv.app`
   - SHA-1: paste the value from step 4.

There is nothing to paste into the code. Google recognises the app by its package name and SHA-1.

> Sign-in shows **error 10**? Step 4 or 5 is wrong, usually the SHA-1 doesn't match the APK you installed. If you build the APK on another computer, its SHA-1 will be different and you need to register that one too.

The app only requests the **drive.file** permission, so it can see **only the files it creates itself** (in a folder called `PocketCCTV`), never the rest of your Drive.

## Step 3: Use it

**Camera phone**
1. Open the app → **This device is the Camera**.
2. Tap **Sign in with Google** and choose your account.
3. Tap **🔋 Allow running in background** and allow it (important, otherwise Android may stop the camera).
4. Give the camera a name, then tap **▶ Start camera** and allow camera access.
5. Note the **4-digit PIN** on screen. Leave the phone plugged in, connected to Wi-Fi. The screen can be off.

**Tablet**
1. Open the app → **This device is the Monitor**.
2. Your camera appears in the list. Type the **PIN** and tap the camera name.
3. For old recordings tap **🎞️ Recordings (Google Drive)**, sign in with the **same Google account**, then tap a clip.

## Watching from outside your home

Live view uses your Wi-Fi network directly. To watch from elsewhere without opening your router, install the free **Tailscale** app on both devices, then in Monitor mode type the camera's Tailscale address (for example `100.x.y.z:8765`) in the address box. Recordings on Google Drive work from anywhere with no extra setup.

## Good to know

- **Latest clip**: a clip appears in Google Drive only when its 2 hours are complete and uploaded. For "right now", use live view.
- **Night vision**: the camera records in black and white (when the phone supports it) and the monitor tints it green. The Drive files themselves are black and white. The green look can be switched off.
- **Size**: a 2-hour clip is about 700 to 800 MB, so a few GB per day uploads over Wi-Fi. There is a "Back up on Wi-Fi only" switch.
- **Phone-specific limits**: some very old phones cannot record and stream live at the same time. The app then keeps recording and tells you live view is unavailable.
- If the camera phone loses power mid-clip, that unfinished clip is lost. Finished clips are safe.
- Local copies older than the retention period are also cleaned up, so the phone's storage can't fill up forever if Drive is unreachable.
- Live video is unencrypted on your home network and protected by the PIN only. Use Tailscale for anything beyond home Wi-Fi.

## Project layout

```
app/src/main/java/com/pocketcctv/app/
  MainActivity.kt        pick Camera or Monitor
  CameraActivity.kt      camera screen (start/stop, PIN, Drive, settings)
  RecorderService.kt     background engine: 480p/30fps, 2-hour clips, live stream
  UploadWorker.kt        Drive backup, verification, deletion, cleanup
  DriveClient.kt         Google sign-in + Drive upload/verify/list/delete
  MjpegServer.kt         live video server on the camera phone
  MjpegClient.kt         live video receiver on the tablet
  Nsd.kt                 automatic camera discovery on Wi-Fi
  MonitorActivity.kt     live view screen
  PlaybackActivity.kt    recordings list and player
  NightVision.kt, Prefs.kt, Storage.kt, CctvState.kt
```
