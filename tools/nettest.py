#!/usr/bin/env python3
"""Network test for a live Shadow session: finds Shadow's remote endpoints from the Googlebook's
socket tables and measures round-trip time / jitter to them, plus Wi-Fi link quality.
Usage: nettest.py ADB_SERIAL"""
import ipaddress, os, re, statistics, subprocess, sys

serial = sys.argv[1]
# Your Android user ID (adb shell am get-current-user); 10 on my Googlebook.
user = os.environ.get("ANDROID_USER_ID", "10")
if not user.isdigit():
    sys.exit("ANDROID_USER_ID must be a number")

def sh(cmd, timeout=120):
    return subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout

m = re.search(r"uid:(\d+)", sh(f"pm list packages -U --user {user} com.blade.shadowcloudgaming"))
if not m:
    sys.exit("Shadow PC is not installed for this user.")
uid = m[1]

def decode(hexaddr):
    ip, port = hexaddr.split(":")
    raw = bytes.fromhex(ip)
    # /proc/net stores each 32-bit word in host (little-endian) order
    raw = b"".join(raw[i:i + 4][::-1] for i in range(0, len(raw), 4))
    addr = ipaddress.ip_address(raw)
    if addr.version == 6 and addr.ipv4_mapped:
        addr = addr.ipv4_mapped
    return addr, int(port, 16)

remotes = {}
for proto in ("udp", "udp6", "tcp", "tcp6"):
    for line in sh(f"cat /proc/net/{proto}").splitlines()[1:]:
        f = line.split()
        if len(f) < 8 or f[7] != uid:
            continue
        addr, port = decode(f[2])
        if addr.is_unspecified or addr.is_loopback:
            continue
        drops = int(f[-1]) if proto.startswith("udp") else 0
        remotes.setdefault(str(addr), []).append(f"{proto}:{port}" + (f" drops={drops}" if drops else ""))

if not remotes:
    sys.exit("No Shadow network connections found. Start a Shadow session first, then rerun.")

print("Shadow PC connections:")
for a, socks in remotes.items():
    print(f"  {a}  ({', '.join(sorted(set(socks)))})")

wifi = sh("cmd wifi status")
rssi = re.search(r"RSSI: (-?\d+)", wifi)
link = re.search(r"Link speed: (\d+Mbps)", wifi)
freq = re.search(r"Frequency: (\d+MHz)", wifi)
print(f"\nWi-Fi: RSSI {rssi[1] if rssi else '?'} dBm, link {link[1] if link else '?'}, {freq[1] if freq else '?'}")

print("\nRound-trip from the Googlebook (100 pings, 0.2 s apart):")
for a in remotes:
    ping = "ping6" if ":" in a else "ping"
    out = sh(f"{ping} -c 100 -i 0.2 -W 2 {a}", timeout=90)
    times = [float(t) for t in re.findall(r"time=([\d.]+)", out)]
    if not times:
        print(f"  {a}: no ping replies (the server blocks ping). Shadow's own on-screen stats will show its latency")
        continue
    loss = 100 - len(times)
    jitter = statistics.mean(abs(x - y) for x, y in zip(times, times[1:])) if len(times) > 1 else 0
    s = sorted(times)
    print(f"  {a}: avg {statistics.mean(times):.1f} ms, median {s[len(s)//2]:.1f}, p95 {s[int(len(s)*.95)-1]:.1f}, "
          f"max {s[-1]:.1f} ms, jitter {jitter:.1f} ms, loss {loss}%")
