# CardputerZero Remote

Control an M5Stack **CardputerZero** from an Android phone over Wi-Fi or Bluetooth. You see its screen live on the phone and type on it, like the Flipper Zero phone app.

```
 phone (Android app)  ──Wi-Fi (TCP 7878)──▶  cpz_remote.py on the CardputerZero
                      ──or Bluetooth ─────▶
   shows the screen   ◀── LCD frames ──────   reads /dev/fb0 (320x170)
   on-screen keyboard ─── key events ────▶   injects into the keyboard's /dev/input node
```

- **Wi-Fi:** fastest. The phone and the CardputerZero must be on the same network; your phone's hotspot works too.
- **Bluetooth:** needs no network, but screen updates are slower. The CardputerZero's Bluetooth range is short, so keep the phone close, ideally within about a metre.

## 1. Install the daemon on the CardputerZero

Enable SSH (in M5 Imager, or Settings on the device). Find the device's IP in the **IP Panel** app, then from this folder run:

```sh
scp -r device <user>@<device-ip>:~/cpz-remote
ssh <user>@<device-ip> 'sh ~/cpz-remote/install.sh'
```

You can use ADB over USB instead: set the USB switch to Slave and enable Settings > Developer > ADB. Then:

```sh
adb push device /tmp/cpz-remote
adb shell sh /tmp/cpz-remote/install.sh
```

The daemon now starts at every boot. Useful commands on the device:

```sh
journalctl -u cpz-remote -f              # live log
sudo cpz_remote.py --info                # framebuffer + keyboard info
sudo systemctl stop cpz-remote && sudo cpz_remote.py --dump-keys   # show codes of keys you press
```

Options go in `/etc/default/cpz-remote`, for example:
- `CPZ_ARGS="--pin 1234"` requires a PIN.
- `--no-bluetooth` turns Bluetooth off.
- `--bt-hidden` keeps the device from showing up in Bluetooth scans. Phones that already know its address can still connect.

Run `sudo systemctl restart cpz-remote` after changing it. `cpz_remote.py --help` lists all options.

## 2. Build and install the Android app

You need Docker and adb. No Java or Android SDK is needed on your computer.

```sh
./build-apk.sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads the Android SDK and Gradle, about 1–2 GB, so it takes a while. Later builds reuse `.build-cache/`.

## 3. Use it

1. Open **Cardputer Remote** and tap **Scan**. The list shows each way to reach the device ("Wi-Fi 192.168.x.x" or "Bluetooth F0:40:…"). You can also type an IP or a Bluetooth address.
   - The first time, Android asks for the "Nearby devices" permission. Allow it for Bluetooth.
   - Finding the device over Bluetooth works only from very close. A Wi-Fi scan also reports the device's Bluetooth address, and the app always lists the last Bluetooth device it used. You only need to find it once.
2. Enter the PIN if you set one, then tap **Connect**.
3. The CardputerZero's screen appears. Type on the on-screen keyboard:
   - **⇧ Ctrl Alt Opt** are sticky. Tap once and the modifier applies to the next key only. Tap twice to lock it (orange). Tap again to release.
   - **Fn** turns the number row into F1–F12 and ⌫ into Delete.
   - The bottom row has Esc, Home/End, PgUp/PgDn and the arrow keys.
   - **⌨** opens your phone's own keyboard, which is handy for long text. A Bluetooth or USB keyboard attached to the phone also works.

## Troubleshooting

- **Bluetooth connect fails** ("Bluetooth connect failed"). The phone is probably out of range; move it next to the CardputerZero.
  - Check that `journalctl -u cpz-remote` shows `bluetooth: RFCOMM service on channel 22`.
  - Phone logs (`adb logcat | grep -i rfcomm`) showing `PAGE_TIMEOUT` mean a weak signal.
- **Scan finds nothing.** Check that both devices are on the same Wi-Fi, and that `systemctl status cpz-remote` shows the daemon running. If the network blocks broadcasts, type the IP by hand.
- **The screen shows but a key does nothing.** Run `--dump-keys` and press that key on the real keyboard, then compare the code with the table in `android/app/src/main/java/cc/cpz/remote/KeyMap.kt`.
  - Keys the built-in keyboard can't produce are sent automatically through a virtual (uinput) keyboard.
  - `CPZ_ARGS="--uinput"` sends every key that way.
- **Wrong colours.** The app reads the pixel format from the device. If the colours still look swapped, report the output of `cpz_remote.py --info`.

## Security

- **Wi-Fi** is unencrypted TCP, meant for your own network.
- **Bluetooth:** the device is discoverable and accepts automatic pairing, so anyone within range can find it. Unpaired devices can use only the remote service, not, for example, its audio receiver.
- **Set a PIN** with `--pin` if other people are on your network or within Bluetooth range.

## Files

| Path | What it is |
| --- | --- |
| `device/cpz_remote.py` | Daemon. Python 3 standard library only; Bluetooth also uses the preinstalled python3-dbus and python3-gi. |
| `device/cpz-remote.service`, `device/install.sh` | systemd unit and installer |
| `android/` | Android app: Kotlin, no third-party libraries |
| `docker/Dockerfile`, `build-apk.sh` | APK build in Docker |
