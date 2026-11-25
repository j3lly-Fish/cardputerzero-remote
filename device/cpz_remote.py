#!/usr/bin/env python3
"""CardputerZero Remote daemon.

Streams the LCD framebuffer to the Android app over Wi-Fi or Bluetooth and
injects the key presses the app sends back. Python 3 standard library only;
Bluetooth also uses python3-dbus and python3-gi (preinstalled on the device).

Protocol (same over TCP and Bluetooth RFCOMM, big-endian):
  phone -> device  handshake  b"CPZ1" + u8 pin_len + pin
  device -> phone  b"O" (ok) or b"P" (bad PIN, then close)
  device -> phone  b"F" u16 w, u16 h, u16 y0, u16 rows, u8 bpp, u8 flags, u32 len,
                   zlib(pixels of rows y0..y0+rows-1); the first frame is always full
                   b"P" keepalive ping (sent when the screen is idle)
  phone -> device  b"K" u16 linux_keycode, u8 value (1 = down, 0 = up)
Discovery: UDP broadcast b"CPZ?" is answered with b"CPZ! <name>[\tbt=<mac>]"; over
Bluetooth the device is discoverable and offers the RFCOMM service BT_UUID.
"""
import argparse
import fcntl
import glob
import hmac
import os
import re
import socket
import struct
import sys
import threading
import time
import zlib

TCP_PORT = 7878
UDP_PORT = 7879
UINPUT_NAME = "cpz-remote virtual keyboard"
BT_UUID = "6c1f8b52-4a2e-4f0c-9d3a-c2b0e5a7f001"
BT_CHANNEL = 22

EV_SYN, EV_KEY = 0, 1
SYN_REPORT = 0
KEY_A = 30
KEY_CNT = 0x300
EVENT_FMT = "llHHi"  # struct input_event: timeval, type, code, value
EVENT_SIZE = struct.calcsize(EVENT_FMT)


def _ioc(direction, typ, nr, size):
    return (direction << 30) | (size << 16) | (ord(typ) << 8) | nr


FBIOGET_VSCREENINFO = 0x4600
FBIOGET_FSCREENINFO = 0x4602
FIX_FMT = "@16sLIIIIHHHI"  # fb_fix_screeninfo up to and including line_length
UI_SET_EVBIT = _ioc(1, "U", 100, 4)
UI_SET_KEYBIT = _ioc(1, "U", 101, 4)
UI_DEV_SETUP = _ioc(1, "U", 3, 92)
UI_DEV_CREATE = _ioc(0, "U", 1, 0)


def EVIOCGBIT(ev, length):
    return _ioc(2, "E", 0x20 + ev, length)


def _key_names():
    n = {1: "ESC", 12: "MINUS", 13: "EQUAL", 14: "BACKSPACE", 15: "TAB",
         26: "LEFTBRACE", 27: "RIGHTBRACE", 28: "ENTER", 29: "LEFTCTRL",
         39: "SEMICOLON", 40: "APOSTROPHE", 41: "GRAVE", 42: "LEFTSHIFT",
         43: "BACKSLASH", 51: "COMMA", 52: "DOT", 53: "SLASH", 54: "RIGHTSHIFT",
         56: "LEFTALT", 57: "SPACE", 58: "CAPSLOCK", 87: "F11", 88: "F12",
         97: "RIGHTCTRL", 99: "SYSRQ", 100: "RIGHTALT", 102: "HOME", 103: "UP",
         104: "PAGEUP", 105: "LEFT", 106: "RIGHT", 107: "END", 108: "DOWN",
         109: "PAGEDOWN", 110: "INSERT", 111: "DELETE", 113: "MUTE",
         114: "VOLUMEDOWN", 115: "VOLUMEUP", 116: "POWER", 125: "LEFTMETA",
         126: "RIGHTMETA", 127: "COMPOSE", 138: "HELP", 464: "FN"}
    for start, chars in ((2, "1234567890"), (16, "QWERTYUIOP"), (30, "ASDFGHJKL"), (44, "ZXCVBNM")):
        for i, ch in enumerate(chars):
            n[start + i] = ch
    for i in range(10):
        n[59 + i] = "F%d" % (i + 1)
    return n


KEY_NAMES = _key_names()


_log_lock = threading.Lock()


def log(*args):
    with _log_lock:
        print(time.strftime("%H:%M:%S"), *args, flush=True)


# ----------------------------------------------------------------- screen ---

class Framebuffer:
    """Reads the visible area of a Linux framebuffer."""

    def __init__(self, path, geometry=None):
        self.fd = os.open(path, os.O_RDONLY)
        self.fixed = geometry  # (w, h, bpp) when reading a plain file in tests
        if geometry:
            w, h, bpp = geometry
            self.line_length = w * bpp // 8
        else:
            buf = bytearray(128)
            fcntl.ioctl(self.fd, FBIOGET_FSCREENINFO, buf, True)
            self.line_length = struct.unpack_from(FIX_FMT, buf)[-1]

    def geometry(self):
        """Returns (width, height, bpp, xoffset, yoffset, red_offset)."""
        if self.fixed:
            w, h, bpp = self.fixed
            return w, h, bpp, 0, 0, 11 if bpp == 16 else 16
        buf = bytearray(160)
        fcntl.ioctl(self.fd, FBIOGET_VSCREENINFO, buf, True)
        v = struct.unpack_from("40I", buf)
        return v[0], v[1], v[6], v[4], v[5], v[8]

    def grab(self):
        """Returns (w, h, bpp, bgr, pixel_bytes) for what is on screen now."""
        w, h, bpp, xoff, yoff, red_offset = self.geometry()
        bypp = bpp // 8
        row = w * bypp
        ll = self.line_length
        start = yoff * ll + xoff * bypp
        if ll == row:
            data = os.pread(self.fd, row * h, start)
        else:
            blob = os.pread(self.fd, ll * (h - 1) + row, start)
            data = b"".join(blob[i * ll:i * ll + row] for i in range(h))
        if len(data) != row * h:
            raise OSError("short framebuffer read (%d of %d bytes)" % (len(data), row * h))
        return w, h, bpp, red_offset == 0, data


# --------------------------------------------------------------- keyboard ---

def key_bits(path):
    """Set of EV_KEY codes the evdev device supports, or None if unknown."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
    except OSError:
        return None
    try:
        buf = bytearray(KEY_CNT // 8)
        fcntl.ioctl(fd, EVIOCGBIT(EV_KEY, len(buf)), buf, True)
    except OSError:
        return None
    finally:
        os.close(fd)
    return {i for i in range(KEY_CNT) if buf[i // 8] >> (i % 8) & 1}


def device_name(path):
    node = os.path.basename(os.path.realpath(path))
    try:
        with open("/sys/class/input/%s/device/name" % node) as f:
            return f.read().strip()
    except OSError:
        return ""


def find_keyboard():
    """Finds the built-in keyboard's evdev node."""
    def natural(p):
        return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", p)]

    events = sorted(glob.glob("/dev/input/event*"), key=natural)
    by_path = sorted(glob.glob("/dev/input/by-path/*i2c*event*"))
    named = [p for p in events if re.search(r"tca8418|keypad|matrix", device_name(p), re.I)]
    seen = set()
    for p in by_path + named + events:
        real = os.path.realpath(p)
        if real in seen or device_name(real) == UINPUT_NAME:
            continue
        seen.add(real)
        bits = key_bits(real)
        if bits and KEY_A in bits:
            return real
    return None


class KeyInjector:
    """Injects key events into the real keyboard node; falls back to uinput.

    Writing to /dev/input/eventN injects the event into that device, so every
    program reading the built-in keyboard (the launcher uses a fixed path)
    sees it. Codes the real keyboard can't send go through a uinput device.
    """

    def __init__(self, path=None, force_uinput=False):
        self.lock = threading.Lock()
        self.phys_fd = None
        self.phys_bits = None
        self.ui_fd = None
        self.ui_failed = False
        if not force_uinput:
            path = path or find_keyboard()
            if path:
                self.phys_fd = os.open(path, os.O_WRONLY)
                self.phys_bits = key_bits(path)  # None = unknown, assume all
                log("keyboard: injecting into %s (%s)" % (path, device_name(path) or "?"))
            else:
                log("keyboard: built-in keyboard not found, using uinput")
        if self.phys_fd is None:
            self._open_uinput()

    def _open_uinput(self):
        try:
            fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
            fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
            fcntl.ioctl(fd, UI_SET_EVBIT, EV_SYN)
            for code in range(1, 256):
                fcntl.ioctl(fd, UI_SET_KEYBIT, code)
            fcntl.ioctl(fd, UI_SET_KEYBIT, 464)  # KEY_FN
            setup = struct.pack("HHHH80sI", 0x06, 0x1209, 0xC0DE, 1, UINPUT_NAME.encode(), 0)
            fcntl.ioctl(fd, UI_DEV_SETUP, setup)
            fcntl.ioctl(fd, UI_DEV_CREATE)
            self.ui_fd = fd
            log("keyboard: created uinput device '%s'" % UINPUT_NAME)
        except OSError as e:
            self.ui_failed = True
            log("keyboard: uinput unavailable:", e)

    def _fd_for(self, code):
        if self.phys_fd is not None and (self.phys_bits is None or code in self.phys_bits):
            return self.phys_fd
        if self.ui_fd is None and not self.ui_failed:
            self._open_uinput()
        return self.ui_fd if self.ui_fd is not None else self.phys_fd

    def send(self, code, value):
        with self.lock:
            fd = self._fd_for(code)
            if fd is None:
                return
            os.write(fd, struct.pack(EVENT_FMT, 0, 0, EV_KEY, code, value)
                     + struct.pack(EVENT_FMT, 0, 0, EV_SYN, SYN_REPORT, 0))


# ----------------------------------------------------------------- server ---

def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("client closed")
        buf += chunk
    return buf


class Server:
    def __init__(self, fb, keys, pin="", fps=20):
        self.fb = fb
        self.keys = keys
        self.pin = pin
        self.period = 1.0 / max(1, fps)
        self.lock = threading.Lock()
        self.client = None

    def serve(self, port):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("", port))
        srv.listen(4)
        log("listening on TCP port %d" % port)
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self.handle, args=(conn, "%s:%d" % addr, 1), daemon=True).start()

    def handle(self, conn, peer, zlevel):
        """Runs one phone session. zlevel: zlib level (higher for slow links)."""
        try:
            conn.settimeout(5)
            hdr = recv_exact(conn, 5)
            if hdr[:4] != b"CPZ1":
                return conn.close()
            pin = recv_exact(conn, hdr[4])
            if self.pin and not hmac.compare_digest(pin, self.pin.encode()):
                log(peer, "rejected: wrong PIN")
                conn.sendall(b"P")
                return conn.close()
            conn.sendall(b"O")
            conn.settimeout(None)
        except OSError:
            return conn.close()

        with self.lock:  # one phone at a time: the newest wins
            old, self.client = self.client, conn
        if old:
            try:
                old.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        log(peer, "connected")

        stop = threading.Event()
        threading.Thread(target=self.stream, args=(conn, stop, zlevel), daemon=True).start()
        held = set()
        try:
            while not stop.is_set():
                msg = recv_exact(conn, 4)
                if msg[:1] != b"K":
                    break
                code, value = struct.unpack(">HB", msg[1:])
                if 0 < code < KEY_CNT and value in (0, 1):
                    self.keys.send(code, value)
                    (held.add if value else held.discard)(code)
        except OSError:
            pass
        finally:
            stop.set()
            for code in held:  # never leave a key stuck down
                self.keys.send(code, 0)
            with self.lock:
                if self.client is conn:
                    self.client = None
            conn.close()
            log(peer, "disconnected")

    def stream(self, conn, stop, zlevel):
        last, fmt, last_sent = None, None, 0.0
        try:
            while not stop.is_set():
                t0 = time.monotonic()
                try:
                    w, h, bpp, bgr, data = self.fb.grab()
                except OSError as e:
                    log("framebuffer read failed:", e)
                    stop.wait(1)
                    continue
                row = w * bpp // 8
                if (w, h, bpp, bgr) != fmt:
                    y0, y1 = 0, h  # first frame or format change: send everything
                elif data != last:
                    y0, y1 = 0, h  # send only the band of rows that changed
                    while data[y0 * row:(y0 + 1) * row] == last[y0 * row:(y0 + 1) * row]:
                        y0 += 1
                    while data[(y1 - 1) * row:y1 * row] == last[(y1 - 1) * row:y1 * row]:
                        y1 -= 1
                else:
                    y0 = y1 = None
                if y0 is not None:
                    comp = zlib.compress(data[y0 * row:y1 * row], zlevel)
                    conn.sendall(struct.pack(">cHHHHBBI", b"F", w, h, y0, y1 - y0, bpp, int(bgr), len(comp)) + comp)
                    last, fmt, last_sent = data, (w, h, bpp, bgr), t0
                elif t0 - last_sent > 2:
                    conn.sendall(b"P")
                    last_sent = t0
                stop.wait(max(0.0, self.period - (time.monotonic() - t0)))
        except OSError:
            pass
        finally:
            stop.set()
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


_bt_addr = None  # set once the Bluetooth service is up


def bt_address():
    """The Bluetooth MAC, so phones that found us over Wi-Fi can also use Bluetooth."""
    return _bt_addr


def discovery(port, name):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", port))
    log("discovery on UDP port %d as '%s'" % (port, name))
    while True:
        try:
            data, addr = s.recvfrom(64)
            if data.startswith(b"CPZ?"):
                reply = "CPZ! " + name
                bt = bt_address()
                if bt:
                    reply += "\tbt=" + bt
                s.sendto(reply.encode(), addr)
        except OSError as e:
            log("discovery error:", e)
            time.sleep(1)


def bluetooth(server, discoverable):
    """Offers the remote over Bluetooth RFCOMM through BlueZ (runs forever)."""
    try:
        import dbus
        import dbus.mainloop.glib
        import dbus.service
        from gi.repository import GLib
    except ImportError as e:
        log("bluetooth disabled (needs python3-dbus and python3-gi):", e)
        return

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    path = "/cc/cpz/remote"

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, fd, props):
            sock = socket.socket(fileno=fd.take())
            sock.setblocking(True)
            peer = "bt " + str(device).rsplit("dev_", 1)[-1].replace("_", ":")
            threading.Thread(target=server.handle, args=(sock, peer, 6), daemon=True).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, device):
            pass

        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            pass

    class Agent(dbus.service.Object):
        """Accepts automatic ("Just Works") pairing so phones can open an encrypted
        link without typing a code. Unpaired devices may only use our service."""
        iface = "org.bluez.Agent1"

        @dbus.service.method(iface, in_signature="ou", out_signature="")
        def RequestConfirmation(self, device, passkey):
            log("bluetooth: pairing with", str(device).rsplit("dev_", 1)[-1].replace("_", ":"))

        @dbus.service.method(iface, in_signature="o", out_signature="")
        def RequestAuthorization(self, device):
            pass

        @dbus.service.method(iface, in_signature="os", out_signature="")
        def AuthorizeService(self, device, uuid):
            if str(uuid).lower() != BT_UUID:
                raise dbus.DBusException("org.bluez.Error.Rejected", "only the remote service is allowed")

        @dbus.service.method(iface, in_signature="o", out_signature="s")
        def RequestPinCode(self, device):
            raise dbus.DBusException("org.bluez.Error.Rejected", "PIN pairing not supported")

        @dbus.service.method(iface, in_signature="o", out_signature="u")
        def RequestPasskey(self, device):
            raise dbus.DBusException("org.bluez.Error.Rejected", "passkey pairing not supported")

        @dbus.service.method(iface, in_signature="ouq", out_signature="")
        def DisplayPasskey(self, device, passkey, entered):
            pass

        @dbus.service.method(iface, in_signature="os", out_signature="")
        def DisplayPinCode(self, device, pincode):
            pass

        @dbus.service.method(iface, in_signature="", out_signature="")
        def Cancel(self):
            pass

        @dbus.service.method(iface, in_signature="", out_signature="")
        def Release(self):
            pass

    Profile(bus, path)
    Agent(bus, path + "/agent")

    def setup():
        def once(call, *args):  # registering twice (bluetoothd restart races) is fine
            try:
                call(*args)
            except dbus.DBusException as e:
                if "AlreadyExists" not in e.get_dbus_name():
                    raise

        try:
            root = bus.get_object("org.bluez", "/org/bluez")
            once(dbus.Interface(root, "org.bluez.ProfileManager1").RegisterProfile, path, BT_UUID, {
                "Name": "CardputerZero Remote",
                "Role": "server",
                "Channel": dbus.UInt16(BT_CHANNEL),
                "RequireAuthentication": False,
                "RequireAuthorization": False,
                "AutoConnect": False,
            })
            agents = dbus.Interface(root, "org.bluez.AgentManager1")
            once(agents.RegisterAgent, path + "/agent", "NoInputNoOutput")
            agents.RequestDefaultAgent(path + "/agent")
            global _bt_addr
            objs = dbus.Interface(bus.get_object("org.bluez", "/"),
                                  "org.freedesktop.DBus.ObjectManager").GetManagedObjects()
            for p, ifaces in objs.items():
                if "org.bluez.Adapter1" in ifaces:
                    _bt_addr = str(ifaces["org.bluez.Adapter1"].get("Address", "")).upper() or None
                    if discoverable:
                        props = dbus.Interface(bus.get_object("org.bluez", p), "org.freedesktop.DBus.Properties")
                        props.Set("org.bluez.Adapter1", "DiscoverableTimeout", dbus.UInt32(0))
                        props.Set("org.bluez.Adapter1", "Discoverable", True)
                    break
            log("bluetooth: RFCOMM service on channel %d%s"
                % (BT_CHANNEL, ", discoverable" if discoverable else ""))
        except dbus.DBusException as e:
            log("bluetooth setup failed, retrying:", e.get_dbus_message() or e)
            GLib.timeout_add_seconds(5, setup)
        return False

    # Re-register whenever bluetoothd (re)starts.
    bus.watch_name_owner("org.bluez", lambda owner: owner and GLib.idle_add(setup))
    GLib.MainLoop().run()


# ------------------------------------------------------------------- main ---

def dump_keys(path):
    print("Reading %s (%s). Press keys on the device; Ctrl+C to stop." % (path, device_name(path) or "?"))
    with open(path, "rb", buffering=0) as f:
        while True:
            _, _, typ, code, value = struct.unpack(EVENT_FMT, f.read(EVENT_SIZE))
            if typ == EV_KEY:
                state = ("up", "down", "repeat")[value] if value in (0, 1, 2) else value
                print("code %4d  %-12s %s" % (code, KEY_NAMES.get(code, "?"), state), flush=True)


def main():
    ap = argparse.ArgumentParser(description="CardputerZero remote control daemon")
    ap.add_argument("--port", type=int, default=TCP_PORT, help="TCP port (default %(default)s)")
    ap.add_argument("--udp-port", type=int, default=UDP_PORT, help="discovery UDP port (default %(default)s)")
    ap.add_argument("--no-discovery", action="store_true", help="don't answer discovery broadcasts")
    ap.add_argument("--no-bluetooth", action="store_true", help="don't offer the remote over Bluetooth")
    ap.add_argument("--bt-hidden", action="store_true",
                    help="don't make the device discoverable (phones that already know it can still connect)")
    ap.add_argument("--name", default=socket.gethostname(), help="name shown in the app's scan list")
    ap.add_argument("--pin", default=os.environ.get("CPZ_PIN", ""), help="require this PIN from the app")
    ap.add_argument("--fps", type=int, default=20, help="max screen updates per second")
    ap.add_argument("--fb", default="/dev/fb0", help="framebuffer device")
    ap.add_argument("--fb-geometry", metavar="WxHxBPP", help="skip fb ioctls (testing with a plain file)")
    ap.add_argument("--input", help="keyboard evdev node (default: auto-detect)")
    ap.add_argument("--uinput", action="store_true", help="inject through a uinput virtual keyboard only")
    ap.add_argument("--info", action="store_true", help="print framebuffer and keyboard info, then exit")
    ap.add_argument("--dump-keys", action="store_true", help="print codes of keys pressed on the device")
    a = ap.parse_args()

    if a.dump_keys:
        path = a.input or find_keyboard()
        if not path:
            sys.exit("no keyboard found; pass --input /dev/input/eventN")
        try:
            dump_keys(path)
        except KeyboardInterrupt:
            pass
        return

    geometry = tuple(int(x) for x in a.fb_geometry.split("x")) if a.fb_geometry else None
    fb = Framebuffer(a.fb, geometry)

    if a.info:
        w, h, bpp, xoff, yoff, red = fb.geometry()
        print("framebuffer %s: %dx%d %dbpp, line_length=%d, offset=(%d,%d), red_offset=%d"
              % (a.fb, w, h, bpp, fb.line_length, xoff, yoff, red))
        kb = a.input or find_keyboard()
        bits = key_bits(kb) if kb else None
        print("keyboard: %s (%s)" % (kb, device_name(kb) if kb else "-"))
        if bits:
            print("keyboard codes:", " ".join(KEY_NAMES.get(c, str(c)) for c in sorted(bits)))
        return

    keys = KeyInjector(a.input, a.uinput)
    if not a.no_discovery:
        threading.Thread(target=discovery, args=(a.udp_port, a.name), daemon=True).start()
    if a.pin:
        log("PIN required")
    server = Server(fb, keys, a.pin, a.fps)
    if not a.no_bluetooth:
        threading.Thread(target=bluetooth, args=(server, not a.bt_hidden), daemon=True).start()
    server.serve(a.port)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
