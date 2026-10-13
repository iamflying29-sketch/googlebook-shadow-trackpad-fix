# Shadow Trackpad Fix

Shadow Trackpad Fix makes the trackpad of my Lenovo Googlebook 15 work properly inside the Shadow PC cloud computer
app on GooglebookOS. Two-finger scrolling becomes smooth and precise, including slow scrolling, sideways scrolling and
flicks with momentum. Pinch to zoom works, three- and four-finger swipes switch windows and go back and forward, and
taps give left, right and middle clicks. The project has four parts that live together in this repository: a helper
that runs on GooglebookOS with adb shell permissions, a companion app that keeps the helper running, a Linux Terminal
command called `shadow-trackpad` with a built-in doctor, and Trackpad Probe, the diagnostic app that proved the root
cause.

## What it does

When I use Shadow PC on my Googlebook, the trackpad now behaves like a Windows precision touchpad inside the stream:

- Two-finger scrolling is smooth in every direction, slow scrolls are no longer lost, and a flick keeps gliding with
  momentum until I touch the pad again.
- Pinching zooms, the same way a Windows precision touchpad zooms (Ctrl plus fine-grained scrolling).
- A three-finger swipe sideways opens the Windows window switcher, and four fingers sideways go back and forward.
- Tapping with one, two or three fingers gives a left, right or middle click, and clicking the pad with two or three
  fingers does the same. A thumb resting at the bottom edge of the pad does not turn a click into a right click.
- My GooglebookOS trackpad settings for natural scrolling, tap to click, tap dragging and pointer speed are followed
  automatically.

The project has four parts:

- **The helper** runs on GooglebookOS as the adb shell user. It switches itself on only while Shadow has captured the
  pointer, and it gives the trackpad back to GooglebookOS the moment the capture ends.
- **The Shadow Trackpad Fix app** starts the helper after every reboot and app update, checks it every 15 minutes,
  and tells me when Shadow PC or GooglebookOS has been updated since the fix was last verified.
- **The `shadow-trackpad` command** in the Linux Terminal controls the helper, changes its settings, pairs and
  installs the app, and runs a doctor that re-checks everything after an update.
- **Trackpad Probe** is the small diagnostic app that proved the root cause. It is now the test target for the
  helper's self-test and for the doctor.

## The problem

In Shadow PC, slow two-finger scrolling did nothing at all, and normal scrolling was jerky and lost much of its
movement. Pinch, flick and multi-finger swipes did not work. Turning on Shadow's Accessibility option did not help,
because Shadow's Accessibility service only handles keyboard events.

Two things combine to cause it:

1. **Relative pointer capture.** Shadow PC 3.35.2 captures the pointer with Android's older
   `View.requestPointerCapture()` call whenever the input device reports itself as a mouse, and the Googlebook's
   trackpad reports itself as both a mouse and a touchpad. On this GooglebookOS build, which is based on Android 17,
   pointer capture has modes, and the older call now gets relative mode by default (the system flag
   `relative_capture_mode_by_default`). In relative mode, Android's input pipeline turns trackpad input into plain
   cursor moves, button presses and scroll steps only. Pinch, fling and three- and four-finger swipes are dropped, and
   the raw finger data that Shadow's own touchpad gesture code needs never reaches the app, so that code never runs.
2. **Truncated scroll steps.** The scrolling that does arrive comes as small fractional wheel steps, roughly 0.1 to 2.2
   per event with a median around 1.0. Shadow converts each step with `(int) value * 120`. The cast to a whole number
   happens before the multiplication, so every step smaller than 1.0 becomes zero, and a step of 1.7 becomes exactly
   one notch.

Trackpad Probe proved this. It captures the pointer exactly the way Shadow does and logs every event it receives.
With capture on, 100% of slow scrolls and 30% to 85% of normal scrolls were lost, and pinch, flick and swipes
produced nothing at all. I ruled out the trackpad hardware, my settings, the network and system load. The system flag
behind relative mode cannot be changed from the adb shell, so it cannot be switched off without root.

The real fix belongs in Shadow itself, which could keep the fractional scroll steps or request absolute capture mode.
Until that happens, this project works around it.

## How I solved it

### The helper takes over the trackpad, but only for Shadow

The helper (`helper/src/local/shadowtrackpad`) is a small Java program that runs as the adb shell user through
`app_process`, the same way scrcpy's server runs. Its code ships inside the companion app's APK, and an on-device
supervisor script (`helper/device/start.sh`, installed into `/data/local/tmp/shadowtrackpad`) starts it from there and
restarts it if it ever exits.

1. **It watches which app is focused.** Every 100 milliseconds it asks the activity manager for the focused task, a
   cheap call that takes about 0.1 milliseconds.
2. **It checks pointer capture only while Shadow is focused.** Whether Shadow holds pointer capture is only visible in
   the output of `dumpsys input`, so the helper reads it only while Shadow is the focused app. It understands several
   output formats. If it cannot read any of them after some future update, it stands down and reports the problem
   instead of guessing.
3. **It finds the trackpad by what it is.** At start it reads `getevent -p` and picks the device that has multitouch
   X and Y axes and a click button, is marked as a pointer device (or is named as a touchpad), and is not a
   touchscreen. It does not rely on a device number. It also reads the pad's resolution and height.
4. **It grabs the trackpad exclusively.** While Shadow holds pointer capture, the helper opens the trackpad's event
   device and takes an exclusive grab (`EVIOCGRAB`), so GooglebookOS no longer sees the trackpad. When Shadow loses
   focus or releases the pointer, the helper closes the device and GooglebookOS gets the trackpad back. If the helper
   dies, the kernel drops the grab by itself.
5. **It rebuilds the fingers from raw multitouch data.** The kernel reports only what changed, so right after a grab
   the helper cannot know where fingers that are already on the pad are. The parser (`MtParser.java`) only passes a
   finger on once both of its coordinates are known for certain, and a contact that started before the grab can never
   become a tap. This keeps one finger's position from ever being mixed up with another's, so the cursor never jumps.
6. **It runs its own gesture engine.** The engine (`Engine.java`) turns finger frames into cursor motion with gentle
   acceleration, scrolling with momentum, pinch, swipes, clicks and taps.

### Getting the events into Shadow

The injector (`Injector.java`) builds events that Shadow already handles well and injects them through Android's
input manager, which the adb shell user is allowed to do:

- **Cursor moves and clicks** are sent as captured relative-mouse events. Android delivers captured-pointer events only
  to the window that holds pointer capture. Android also merges consecutive moves that arrive within one display frame,
  and Shadow reads only the newest sample of such a batch, so some movement would be lost. Moves whose pointer ID
  differs are never merged, so the helper alternates the ID of every move between 0 and 1, and Shadow ignores the ID.
- **Instant delivery.** Android holds each move until the app's next display frame, about 9 milliseconds. An event that
  cannot be merged makes Android hand the waiting move over at once. After every move, the helper therefore sends the
  release of a mouse button that is not held. Shadow's button logic only reacts to a change of the button state, so it
  ignores this event completely, and the move arrives in under a millisecond. The `low_latency` setting controls this.
- **Scrolling** is sent as a synthetic two-finger touchpad drag. Shadow's own touchpad gesture code turns that drag into
  fine-grained Windows wheel deltas, where the travel of the synthetic fingers is exactly the wheel delta (120 is one
  notch). Shadow opens its on-screen keyboard when a two-finger contact lasts less than 100 milliseconds, so the
  synthetic fingers always stay down for at least 130 milliseconds by default. A short scroll is lifted a little later
  by a timer, so nothing else waits for it.
- **Pinch** is sent as Ctrl held down plus that same drag, which is how Windows precision touchpads zoom.
- **Swipes** are sent as keyboard shortcuts. GooglebookOS intercepts injected Windows-key shortcuts and Alt+Tab (on my
  Googlebook, an injected Win+Tab opened Android's Overview, Alt+Tab opened Android's own switcher, and Win+D never
  arrived). Ctrl+Alt+Tab and Alt+Left and Alt+Right do reach Shadow, so the swipes use those. The helper also checks
  whether Android has reserved any of these shortcuts as system shortcuts, and if it has, it switches the matching
  gestures off instead of triggering a surprise action.

It is fast. In an A/B test, the time from my finger to the app was 9.4 milliseconds with the helper and 11.2
milliseconds with Android's own handling, with no lost movement.

### Built to be safe and to keep working

- **Watchdog.** If the helper's main loop stalls for 1.5 seconds while it holds the trackpad, a watchdog thread drops
  the grab so the trackpad works in GooglebookOS again, and held keys and buttons are released as soon as the loop
  runs. After 5 seconds the process exits, and the supervisor starts a fresh one.
- **Stale capture check.** If the capture check itself stops answering for 3 seconds, for example because a system
  command hangs, the helper releases the trackpad instead of trusting an old answer. Every system command it runs is
  killed after 5 seconds.
- **Fail-safe.** If Android rejects more than 20 injected events in a row, the helper releases the trackpad and
  restarts. If that happens twice within 10 minutes, it leaves the trackpad alone for an hour.
- **Nothing left held.** Every mode change finishes the running gesture first, so Ctrl, a mouse button, the synthetic
  drag or the window switcher can never be left behind.
- **Locked-down control.** The helper's control socket only accepts commands that carry a secret token from a file
  that only the shell user can read. Its read-only status port on `127.0.0.1` (port 51742, or 51743 if that one is
  taken) answers only a status request with a random nonce, signs its reply with that token, and says nothing about
  other apps.

### The companion app keeps it running

Programs started through adb live in adbd's process group and stop when adbd stops or the device reboots, so the
helper cannot survive a reboot on its own. The Shadow Trackpad Fix app (`app/`) handles that:

1. It runs a check after every boot, after every update of the app (with a restart, so the new helper code is used),
   every 15 minutes, and a few seconds after Wireless debugging, USB debugging or Developer options are switched.
2. It asks the helper for its status over the signed status port. A healthy helper is left alone. A missing helper is
   started, and a running but unhealthy helper is restarted at most once an hour.
3. To start the helper, the app connects to Wireless debugging on `127.0.0.1` with its own minimal adb client
   (`AdbClient.java`) and its own paired key. It finds the current Wireless debugging port through the network
   service discovery that Wireless debugging advertises, and it only accepts ports that belong to this device.
4. Before it sends anything over that connection, it proves that the other end is the real adb service. It runs an
   `am broadcast` with a random nonce to its own receiver, which only senders holding the `DUMP` permission (the adb
   shell and the system) can reach. Only if the nonce arrives does the app install the bundled supervisor script,
   write the helper's token and run the supervisor.
5. It publishes Wireless debugging's current address and port in `Documents/Shadow Trackpad Fix/adb-port.txt`,
   signed with a secret that only the app and the Linux Terminal can compute. That secret is the SHA-256 hash of an RSA
   signature over the fixed text `shadowtrackpadfix-v1`, made with the paired key. The `shadow-trackpad` command uses
   that file to reconnect after a reboot, when the port has changed.
6. It posts a notification when the fix needs attention, and another one when Shadow PC or GooglebookOS has changed
   since the doctor last verified the fix. The app only notifies. Fallbacks are applied only when you run the doctor.

### The doctor re-checks everything after an update

`shadow-trackpad doctor` (`tools/doctor.py`) runs in the Linux Terminal and re-verifies the whole fix:

1. It checks the helper's health on the Googlebook, that the app is not blocked by Android, and that the app answers
   background requests.
2. It runs live tests with the Trackpad Probe window: capture detection and the exclusive grab, the helper's built-in
   self-test (cursor delivery, no lost movement, instant delivery, smooth scroll, flick, pinch and clicks), and a key
   test that confirms Android still lets the gesture shortcuts through to apps.
3. It pulls the installed Shadow PC APK, decompiles the relevant input classes with jadx, and checks every assumption
   the helper relies on, for example that the two-finger touchpad drag still becomes fine-grained wheel scrolling,
   that the no-op button release is still ignored, and how long Shadow's two-finger keyboard trap is.
4. If an assumption breaks, it switches that feature to a safe fallback: whole-notch scrolling, normal delivery, a
   longer hold for the synthetic fingers, or gestures off. When a later run finds the feature working again, it
   removes only the fallbacks it set itself.
5. If every critical check passes, it records the Shadow PC version and the GooglebookOS build as verified, which also
   silences the app's update notification.

In my final doctor run, all 25 checks passed.

### Testing

Before I called it done, the code went through three rounds of independent review by AI code reviewers. I also wrote a test harness
(`tools/fuzz`) that builds the real engine, injector and parser with a controllable clock and an event validator. It
ran more than one million random gesture sequences through the engine with zero violations and zero stuck keys or
buttons, and hundreds of thousands of sequences through a model of the kernel's multitouch reporting, also without a
single mixed-up finger. A separate security test attacks the running helper's control socket and status port.

## Requirements

- A Lenovo Googlebook 15 (x86-64). I built and tested everything on GooglebookOS build CL3B.260622.271.R1, which is
  based on Android 17. Other devices or builds may behave differently.
- Shadow PC (`com.blade.shadowcloudgaming`). I tested with version 3.35.2 (build 335020).
- Developer options and Wireless debugging turned on, on a Wi-Fi network where GooglebookOS allows Wireless debugging.
  Keeping USB debugging on is recommended (see the installation steps).
- The Linux Terminal with `adb`, `python3`, `openssl`, a JDK (for `javac` and `keytool`), and the Android SDK
  build-tools 35.0.0 and platform android-35 in `~/tools/android-sdk`. The shared
  [getting-started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md)
  covers all of these except `openssl`.
- For the doctor only: jadx (I used version 1.5.1) in `~/tools/jadx`.

No root is needed. Everything uses the permissions that the adb shell user already has.

## Installation

1. **Do the shared setup.** Follow the
   [getting-started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md):
   turn on Developer options and the Linux Terminal, install adb and the JDK, connect to Wireless debugging, find your
   Android user ID, and install the Android SDK build tools.

2. **Install openssl** if it is not there yet. The `shadow-trackpad` command uses it to work with the app's paired key.

   ```bash
   sudo apt install openssl
   ```

3. **Get the code.**

   ```bash
   git clone https://github.com/iamflying29-sketch/googlebook-shadow-trackpad-fix.git ~/googlebook-shadow-trackpad-fix
   ```

4. **Put the command on your PATH.** Use a symbolic link, so the command can find the rest of the repository.

   ```bash
   mkdir -p ~/.local/bin
   ln -s ~/googlebook-shadow-trackpad-fix/bin/shadow-trackpad ~/.local/bin/shadow-trackpad
   ```

5. **Set your values.** The command needs your Googlebook's IP address (the part before the colon under
   **IP address & Port** on the Wireless debugging screen) and your Android user ID. The build scripts need a password
   for your own signing keystores, which they create on the first build. Add these lines to `~/.bashrc`, with your own
   values:

   ```bash
   export PATH="$HOME/.local/bin:$PATH"
   export GOOGLEBOOK_IP=192.0.2.10          # your Googlebook's IP address (192.0.2.10 is only an example)
   export ANDROID_USER_ID=10                # the number printed by: adb shell am get-current-user
   export KS_PASS='choose-your-own-password' # password for the signing keystores that the build scripts create
   ```

   Then load them with `source ~/.bashrc`. Keep the same `KS_PASS` for later builds, because every update has to be
   signed with the same keystore.

6. **Connect adb to your Googlebook** as described in the guide, if it is not connected yet:

   ```bash
   adb connect 192.0.2.10:41000   # use the IP address and port from your Wireless debugging screen
   ```

7. **Build and install the app.**

   ```bash
   shadow-trackpad install-app
   ```

   This builds `app/ShadowTrackpadFix.apk` (with a new, always increasing version code), installs it for your user,
   and grants it `WRITE_SECURE_SETTINGS` (so it can switch Wireless debugging back on), notifications and local
   network access. It also exempts the app from permission auto-revoke, background restrictions and battery
   optimization, so its boot and periodic checks can run. On this first run it ends with "No paired key ... pair one first", which is expected.

8. **Pair the app's own adb key.**

   ```bash
   shadow-trackpad pair
   ```

   On the Googlebook, open **Settings > System > Developer options > Wireless debugging > Pair device with pairing
   code** and keep the dialog open. Type the six-digit code and the IP address and port from that dialog when the
   command asks for them. The command creates a dedicated key in `~/.config/shadow-trackpad/adbhome`, pairs it through
   a temporary adb server that it shuts down afterwards, and hands the key to the app. The pairing shows up as
   `ShadowTrackpadFix@googlebook` under **Paired devices**. Within a minute or so, the app starts the helper.

9. **Check that it runs.**

   ```bash
   shadow-trackpad status
   ```

   The first line should start with `running`, the second should be `HEALTH: ok`, and the last should be
   `supervisor: running`. You can also open the Shadow Trackpad Fix app, which shows the same status.

10. **Recommended: keep USB debugging on.** Because the helper lives in adbd's process group, it stops whenever adbd
    stops, for example when Wireless debugging is switched off. With USB debugging on, adbd keeps running. Turn on
    **USB debugging** in Developer options, then tell the app to switch it back on whenever it has to start the helper
    again (replace `<USER_ID>` with your Android user ID):

    ```bash
    adb shell am broadcast --user <USER_ID> -n local.shadowtrackpadfix/.ControlReceiver \
        -a local.shadowtrackpadfix.CONFIG --ez usb_debugging true
    ```

    The reply contains `keepUsbDebugging=true`. Sending the same broadcast with `false` turns this off again.

11. **Recommended: run the doctor once.** The app can only tell you that Shadow PC or GooglebookOS changed after the
    doctor has verified a version once. The doctor needs Trackpad Probe, a small key test and jadx:

    ```bash
    ~/googlebook-shadow-trackpad-fix/trackpad-probe/build.sh
    ~/googlebook-shadow-trackpad-fix/tools/dex/build.sh
    sudo apt install curl
    mkdir -p ~/tools/jadx
    cd ~/tools/jadx
    curl -LO https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip
    unzip jadx-1.5.1.zip
    cd ~
    ```

    Make sure the Googlebook is awake and unlocked and that Shadow PC is installed, then run:

    ```bash
    shadow-trackpad doctor
    ```

    The doctor installs Trackpad Probe if needed, opens its window for the live tests, and brings the Linux Terminal
    back to the front afterwards. Decompiling Shadow can take a few minutes. The last line says whether all critical
    checks passed.

### Optional: running the helper without the app

The supervisor falls back to `/data/local/tmp/shadowtrackpad/shadowtrackpad.dex` when the app is not installed. This
is how I ran the helper before the app existed. It works until the next reboot or until adbd stops, and the doctor
needs the app, so I only recommend it for development:

```bash
~/googlebook-shadow-trackpad-fix/helper/build.sh
adb shell mkdir -p /data/local/tmp/shadowtrackpad
adb push ~/googlebook-shadow-trackpad-fix/helper/shadowtrackpad.dex /data/local/tmp/shadowtrackpad/
shadow-trackpad start
```

## Using it

### Day to day

There is nothing to do. Open Shadow PC and click into your stream. As soon as Shadow captures the pointer, the helper
takes over the trackpad, and `shadow-trackpad status` shows `ACTIVE (trackpad grabbed for Shadow)`. When you switch to
another app, the trackpad goes back to GooglebookOS within a fraction of a second. When Shadow itself releases the
pointer, the helper notices within about half a second, or within two seconds if your fingers are moving at that
moment.

### Gestures inside Shadow

| Gesture | What Shadow receives |
|---|---|
| Move one finger | Cursor movement with gentle acceleration |
| Click the pad with one finger | Left button (a thumb resting in the bottom 10 mm of the pad is ignored) |
| Click the pad with two fingers | Right button |
| Click the pad with three fingers | Middle button |
| Tap with one finger | Left click. With tap dragging on in GooglebookOS, tap and touch again within 0.3 seconds to drag |
| Tap with two fingers | Right click |
| Tap with three fingers | Middle click |
| Tap one finger while a thumb rests at the bottom | Left click |
| Two-finger drag | Smooth scrolling up, down and sideways; a flick keeps gliding until you touch the pad |
| Pinch | Zoom (Ctrl plus fine-grained scrolling) |
| Three fingers sideways | Windows switcher (Ctrl+Alt+Tab); keep moving to step through windows, lift to pick one |
| Three or four fingers up | Opens the Windows switcher; click a window to pick it |
| Three or four fingers down | Closes a switcher opened by a swipe up in the last 30 seconds (Esc) |
| Four fingers to the right | Back (Alt+Left) |
| Four fingers to the left | Forward (Alt+Right) |

Taps within 0.4 seconds of a key press are ignored, so a palm brushing the pad while you type does not click.

### The shadow-trackpad command

| Command | What it does |
|---|---|
| `shadow-trackpad` or `shadow-trackpad status` | Shows what the helper is doing right now, its health, versions, latency statistics and settings, and whether the supervisor runs |
| `shadow-trackpad start` | Installs the current supervisor script from this repository and starts the helper. This also clears an earlier stop |
| `shadow-trackpad stop` | Stops the helper and its supervisor. It stays stopped, also after reboots, until you start it again, and the app respects that |
| `shadow-trackpad restart` | Stops and starts the helper |
| `shadow-trackpad pause` and `resume` | Gives the trackpad back to Shadow's normal handling, and takes it over again. A pause lasts until the helper restarts |
| `shadow-trackpad log` | Shows the last 60 lines of the helper's log. Add `-f` to follow it |
| `shadow-trackpad set KEY VALUE` | Changes a setting (see below). The helper applies it within 2 seconds |
| `shadow-trackpad config` | Shows your settings file and a short description of the settings |
| `shadow-trackpad reload` | Makes the helper re-read the settings file at once |
| `shadow-trackpad verbose` | Turns detailed gesture logging on or off |
| `shadow-trackpad nettest` | During a Shadow session: shows the helper's latency, Shadow's network connections, Wi-Fi signal and link speed, and the round-trip time, jitter and loss to Shadow's servers (100 pings each) |
| `shadow-trackpad doctor` | Re-verifies the whole fix, as described above |
| `shadow-trackpad install-app` | Rebuilds and reinstalls the app, keeping its pairing, and restarts the helper with the new code |
| `shadow-trackpad pair` | Pairs the app's adb key again, for example after the pairing was removed under Paired devices |
| `shadow-trackpad connect [IP:PORT]` | Connects adb to the given address, or to the address the app published |

`install-app` and `pair` accept `--force` to skip the device check described under Safety, which is needed after you
reinstalled the app or cleared its data.

### Settings

Settings live in `/data/local/tmp/shadowtrackpad/config.properties` on the Googlebook. Change them with
`shadow-trackpad set KEY VALUE`. Values outside the allowed range are clamped, so a typo cannot freeze the cursor.

| Setting | Default | Meaning |
|---|---|---|
| `pointer_speed` | 1.0 | Cursor speed multiplier on top of your GooglebookOS pointer speed (0.1 to 10) |
| `scroll_speed` | 1.0 | Two-finger scroll speed multiplier (0.1 to 10) |
| `scroll_mode` | smooth | `smooth` for fine-grained scrolling, or `notch` for whole mouse-wheel notches |
| `low_latency` | true | Hands each move to Shadow at once instead of at its next display frame |
| `momentum` | true | Lets a flick keep scrolling |
| `flick_boost` | 1.25 | Glide speed of a flick compared with your finger's speed (0.3 to 4) |
| `momentum_friction` | 0.28 | How long a flick glides, in seconds (0.05 to 3) |
| `pinch_speed` | 1.0 | Pinch zoom speed multiplier (0.1 to 10) |
| `natural_scroll` | auto | `auto` follows your GooglebookOS setting, or `true` or `false` |
| `tap_to_click` | auto | `auto`, `true` or `false` |
| `tap_drag` | auto | `auto`, `true` or `false` |
| `gestures` | true | Pinch zoom and the three- and four-finger swipes |
| `typing_tap_block_ms` | 400 | Ignores taps for this many milliseconds after a key press (0 to 5000) |
| `scroll_hold_ms` | 130 | Minimum time the synthetic scroll fingers stay down, in milliseconds (120 to 1000) |
| `packages` | com.blade.shadowcloudgaming | Comma-separated list of the apps the helper serves |

Keep Shadow's own mouse speed setting at 1.0 and use `pointer_speed` instead. The doctor checks that Shadow still
applies its mouse speed slider to every event, and it warns about this.

### The Shadow Trackpad Fix app

The app shows the result of its last check and the helper's full status. **Check / start** runs a check and starts the
helper if needed (it also clears a stop), **Restart** restarts it, **Stop** stops it until you start it again, and
**Wireless debugging settings** opens Developer options. Notifications appear in the "Problems and updates" channel.

### Trackpad Probe

Trackpad Probe is useful on its own if you want to see what the trackpad really delivers to an app. Build and install
it (replace `<USER_ID>` with your Android user ID):

```bash
~/googlebook-shadow-trackpad-fix/trackpad-probe/build.sh
adb install --user <USER_ID> ~/googlebook-shadow-trackpad-fix/trackpad-probe/TrackpadProbe.apk
```

Open it,
press **C** to capture the pointer the way Shadow does, and press **Esc** to release it. Every event appears on the
screen, together with the scroll amount Shadow would lose and send, and it is logged under the tag `TrackpadProbe`:

```bash
adb logcat -s TrackpadProbe:I
```

## Updating, and what happens after app or GooglebookOS updates

- **Updating this project:** get the new code and reinstall the app. The app keeps its pairing, and after the update
  it restarts the helper so the new code runs. A stop that you set stays in place.

  ```bash
  git -C ~/googlebook-shadow-trackpad-fix pull
  shadow-trackpad install-app
  ```

- **After a Shadow PC update or a GooglebookOS update:** the helper keeps running and keeps checking itself. The app
  posts a notification saying that Shadow PC or Android changed. Make sure the Googlebook is awake and unlocked, then
  run `shadow-trackpad doctor`. If something in Shadow changed, the doctor switches the affected feature to its safe
  fallback, and when all critical checks pass, it records the new versions as verified, which clears the notification.
- **If the helper cannot read the pointer-capture state any more** after an update, it stands down by itself (the
  trackpad then behaves as it does without the fix) and reports `capture-state-unknown` in its health line.

## Uninstalling

1. Stop the helper and tell the app it is stopped:

   ```bash
   shadow-trackpad stop
   ```

2. Remove both apps:

   ```bash
   adb uninstall local.shadowtrackpadfix
   adb uninstall local.trackpadprobe
   ```

3. Remove the helper's files on the Googlebook, including the doctor's key test and the fuzz build if you used them:

   ```bash
   adb shell rm -rf /data/local/tmp/shadowtrackpad /data/local/tmp/keytest.dex /data/local/tmp/fuzz.dex
   ```

4. On the Googlebook, open **Settings > System > Developer options > Wireless debugging > Paired devices** and forget
   `ShadowTrackpadFix@googlebook`. With the Files app, delete `adb-port.txt` (and any numbered copy such as
   `adb-port (1).txt`) from `Documents/Shadow Trackpad Fix`.
   Turn off USB debugging and Wireless debugging if you only turned them on for this project.

5. In the Linux Terminal, remove the paired key, the command and the repository, and remove the lines you added to
   `~/.bashrc`:

   ```bash
   rm -rf ~/.config/shadow-trackpad
   rm ~/.local/bin/shadow-trackpad
   rm -rf ~/googlebook-shadow-trackpad-fix
   ```

## Troubleshooting

- **"Not connected to the Googlebook."** Make sure Wireless debugging is on, connect with
  `shadow-trackpad connect IP:PORT` using the address on the Wireless debugging screen, and check that `GOOGLEBOOK_IP`
  is set. Once the app has published its port file, the command finds the new port by itself after a reboot.
- **The command never finds the published port.** It reads the port file from
  `/mnt/shared/Documents/Shadow Trackpad Fix` in the Linux Terminal. If your Linux Terminal shows GooglebookOS's
  Documents folder somewhere else, set `SHADOW_TRACKPAD_DOCS` to that folder.
- **The app says "Not set up yet".** The app has no paired key yet. Run `shadow-trackpad pair`. (`install-app` only
  hands over a key that was paired before.)
- **Notification: "Wireless debugging is off and could not be switched on."** The app only switches Wireless debugging
  back on while Wi-Fi is connected, and at most every 10 minutes. Connect to Wi-Fi on a network where Wireless
  debugging is allowed, or switch it on in Developer options.
- **Notification: "Wireless debugging is on but no longer accepts this app's key."** The pairing was probably removed.
  Run `shadow-trackpad pair`.
- **Notification: "Something on this Googlebook imitates Wireless debugging."** A local port claimed to be Wireless
  debugging but could not prove it, so the app sent nothing to it. If this keeps happening, check recently installed
  apps.
- **"The device at ... could not prove it is your Googlebook with the Shadow Trackpad Fix app."** The command checks
  the device before it sends the paired key. If you reinstalled the app or cleared its data, the app no longer has the
  key, so run the same command again with `--force`.
- **The app does not react to boot or background checks.** If an app crashes twice in a row, Android puts it on its
  "bad processes" list and blocks all of its background starts until you open it by hand. Open the Shadow Trackpad Fix
  app once. The doctor checks for this, and `install-app` opens the app by itself when it gets no answer.
- **The health line is not `HEALTH: ok`.** The status shows one or more of these:
  - `no-trackpad-found`: no device looked like a trackpad. The helper looks again every 10 seconds.
  - `cannot-open-trackpad`: the trackpad was found but could not be opened, which would mean that GooglebookOS has
    removed the adb shell's access to input devices.
  - `capture-state-unknown`: `dumpsys input` no longer has a format the helper understands, so it stands down.
  - `capture-check-failing`: the capture check has not completed for more than 3 seconds.
  - `stood-down:injection-failing` or `stood-down:injection-failing-repeatedly(paused-1h)`: Android kept rejecting
    injected events, so the helper released the trackpad. After two of these within 10 minutes, it leaves the trackpad
    alone for an hour.
- **"status port not verified (another app may be using port 51742)".** The app could not get a correctly signed
  reply from the helper's status port. Another app may be using port 51742 or 51743. The helper itself is not
  affected, but the app cannot see its health without adb until the port is free.
- **The info line says `Ctrl+Alt+Tab-gestures-off` or `Alt+Left/Right-gestures-off`.** GooglebookOS now reserves that
  shortcut, so the helper switched the matching swipes off. Nothing needs fixing.
- **The doctor says the screen is off or locked.** The live tests need the Googlebook awake and unlocked. The doctor
  exits without testing or changing anything, so unlock it and run the doctor again.
- **The doctor cannot locate Shadow's input classes.** Shadow's code was restructured, so the code checks were
  skipped. The helper keeps running, and the failed checks show which part of it needs adapting.
- **Shadow's on-screen keyboard pops up after a short two-finger touch.** Shadow's two-finger keyboard trap may have
  become longer. Run the doctor, which measures it and raises `scroll_hold_ms` if needed, or raise it yourself.
- **The cursor feels faster or slower than in GooglebookOS.** Keep Shadow's own mouse speed at 1.0 and adjust
  `pointer_speed`.
- **Helper log.** `shadow-trackpad log` shows what happened. The log is kept in `/data/local/tmp/shadowtrackpad/daemon.log`
  and rotated at 2 MB.

## Safety and privacy notes

- **No root and no system changes.** The helper runs as the adb shell user and uses only that user's existing access:
  reading input devices and injecting input events. Nothing in GooglebookOS is modified.
- **The trackpad is only taken while Shadow holds pointer capture.** It is released when capture ends, when you pause
  the helper, when the capture check goes stale, on any error, by the watchdog, and by the kernel if the helper exits.
- **Events only go to Shadow.** Captured-pointer events are delivered by Android only to the window holding pointer
  capture. The keyboard shortcuts for swipes and pinch are only sent while the helper has the trackpad, which means
  that Shadow is the focused app.
- **Keyboard timing only.** The helper opens the keyboard's input device without grabbing it, and uses only the timing
  of key presses to ignore accidental taps while you type. It does not record or log which keys you press.
- **The app's permissions.** Internet, network state, Wi-Fi state and multicast (to find and reach Wireless debugging
  on `127.0.0.1`), local network access, starting at boot, notifications, and `WRITE_SECURE_SETTINGS`, which
  `install-app` grants through adb. The app uses `WRITE_SECURE_SETTINGS` only to switch Wireless debugging back on and,
  if you asked for it, to keep USB debugging on. It looks up the installed Shadow PC version to notice updates.
- **What the app runs.** Over adb, the app only installs its bundled supervisor script, writes the helper's token, and
  runs the supervisor's start, stop, restart and status commands.
- **The paired key.** The app keeps its copy of the dedicated adb key in its private storage, with backups turned off.
  The Linux Terminal keeps its copy in `~/.config/shadow-trackpad/adbhome`. Treat that folder like a password: anyone
  who has the key can use Wireless debugging on your Googlebook. You can revoke the pairing at any time under
  **Paired devices**.
- **Proof before trust.** The app proves that a connection reaches the real adb service before it sends anything. The
  `shadow-trackpad` command asks the app to prove that it holds the paired key before it sends the key again, and it
  only follows port files signed with the key-derived secret. The app's setup receiver only accepts broadcasts from
  senders holding the `DUMP` permission, which ordinary apps are not granted.
- **No data collection.** Nothing is sent anywhere outside the Googlebook. The only network traffic the project causes
  beyond `127.0.0.1` and the Linux Terminal's own adb connection is `nettest`, which pings Shadow's servers when you
  run it.

## Limitations and known issues

- It was built and tested on one Googlebook, one GooglebookOS build and one Shadow PC version.
- It depends on Developer options, Wireless debugging and the paired key. After a reboot, the helper can only start
  once Wi-Fi is connected on a network where GooglebookOS allows Wireless debugging. Without USB debugging, the helper
  stops whenever Wireless debugging goes off, and the app restarts it at the next opportunity.
- Gestures that need the Windows key or Alt+Tab are impossible, because GooglebookOS intercepts those shortcuts. The
  swipes use Ctrl+Alt+Tab and Alt+Left and Alt+Right instead.
- **Known issue: `dumpsys input` polling.** While Shadow is the focused app, the helper runs a full `dumpsys input`
  every 0.3 to 2 seconds to read the pointer-capture state. Its output is read while it is written, and each run is
  killed after 5 seconds, but there is no size cap, and a full dump is much more than the helper needs. It also runs
  `dumpsys package` for Shadow every 10 minutes. This does not meet the stricter rule I follow in my later projects,
  which is to ask system services only for the smallest answer, with a deadline and a size cap. In my measurements,
  one `dumpsys input` delayed an input event by at most 3 to 4 milliseconds, which is why it runs only every 2 seconds
  while your fingers are moving.
- The doctor asks for several full `dumpsys` reports (power, window and the process list) once per run.
- A pause is not remembered when the helper restarts.
- The Android user ID is not detected automatically. Set `ANDROID_USER_ID` if yours is not 10.
- `shadow-trackpad config` lists most settings, but not `scroll_hold_ms` and `packages`. Both are described in the
  settings table above.
- This is a workaround. The proper fix is in Shadow PC, which could keep fractional scroll steps or request absolute
  capture mode.

## Files in this repository

| Path | What it is |
|---|---|
| `helper/src/local/shadowtrackpad/` | The helper: `Main` (control socket, status port, log), `Daemon` (capture watching, grab, watchdog), `MtParser` (raw multitouch), `Engine` (gestures), `Injector` (events for Shadow), `Config`, `Latency`, `SelfTest` |
| `helper/device/start.sh` | On-device supervisor: start, stop, ensure, restart, status, and the restart loop |
| `helper/build.sh` | Builds `helper/shadowtrackpad.dex` for running the helper without the app |
| `app/` | The Shadow Trackpad Fix app: manifest, icons, sources and `build.sh` |
| `app/build.sh` | Builds `app/ShadowTrackpadFix.apk` with the helper inside, copies `helper/device/start.sh` into `app/assets/` (generated, not committed) and creates `app/keystore.jks` on the first build |
| `trackpad-probe/` | Trackpad Probe: manifest, sources and `build.sh` |
| `bin/shadow-trackpad` | The Linux Terminal command |
| `tools/doctor.py` | The doctor |
| `tools/nettest.py` | The network test |
| `tools/dex/KeyTest.java`, `tools/dex/build.sh` | The doctor's keyboard shortcut test and its build script (produces `keytest.dex`) |
| `tools/fuzz/` | Test harness: `build-fuzz.sh`, `hooks.py`, `Fuzz`, `ParserFuzz`, `SecurityTest`, `Clock` |
| `LICENSE` | MIT License |

Each build script writes into a new dated folder under its own `build/` folder and never deletes anything, so old
build folders stay until you remove them yourself. Build outputs, keystores and keys are excluded by `.gitignore` and
are never part of this repository.

### Running the test harness

The harness runs on the Googlebook, because the engine needs Android's framework classes. `build-fuzz.sh` copies the
current helper sources to a new dated folder under `/tmp/shadowtrackpad-fuzz-build`, swaps in the controllable clock
and the validator, and compiles them. It prints the path of the finished `classes.dex`, and it never changes the real
sources. The tests are CPU heavy, so start with a small number of sequences:

```bash
adb push "$(~/googlebook-shadow-trackpad-fix/tools/fuzz/build-fuzz.sh | tail -1)" /data/local/tmp/fuzz.dex
adb shell CLASSPATH=/data/local/tmp/fuzz.dex app_process / local.shadowtrackpad.Fuzz 10000 1
adb shell CLASSPATH=/data/local/tmp/fuzz.dex app_process / local.shadowtrackpad.ParserFuzz 10000 1
adb shell CLASSPATH=/data/local/tmp/fuzz.dex app_process / local.shadowtrackpad.SecurityTest
```

The last number is the random seed. `Fuzz trace SEED` and `ParserFuzz trace SEED` replay one sequence frame by frame.
`SecurityTest` needs the helper to be running.

## Related projects

- [Googlebook Projects](https://github.com/iamflying29-sketch/googlebook-projects): the overview of all my Googlebook
  projects and the shared [getting-started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md).
- [Sleep Guard](https://github.com/iamflying29-sketch/googlebook-sleep-guard): another of my GooglebookOS helpers that
  runs with adb shell permissions and is kept alive by its own app.
- [Waydroid](https://github.com/iamflying29-sketch/googlebook-waydroid): if Waydroid also shows up
  as an adb device, `shadow-trackpad` still sends its commands only to the Googlebook itself.

## Disclaimer and license

This is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by Google, Lenovo or
any other company named here. All product names belong to their owners. Released under the MIT License.
Copyright (c) 2026 Thomas Liviakis.
