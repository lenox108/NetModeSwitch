#!/usr/bin/env python3
"""Check the compiled route scripts with real Android iptables; refuses physical devices.

Build :app:assembleModernDebug first. Run only on an idle, disposable userdebug emulator:
  python3 tools/check-tethering-emulator.py --serial emulator-5580 --output /tmp/nms-route-qa
No mobile data, VPN credentials, app preferences or global IPv6 switches are used.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--native-hev", action="store_true", help="Also parse the generated YAML using the bundled arm64 HEV")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only an explicitly selected disposable emulator is allowed")
    output = args.output or Path(tempfile.mkdtemp(prefix="nms-route-qa-"))
    output.mkdir(parents=True, exist_ok=True)
    adb = str(Path(args.sdk) / "platform-tools/adb")

    def device(*command, check=True, data=None):
        return subprocess.run([adb, "-s", args.serial, *command], input=data, text=True,
                              capture_output=True, check=check, timeout=30)

    if device("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        parser.error("Target did not identify itself as an emulator")

    def root(script, check=True):
        return device("shell", "su", "0", "sh", check=check, data=script + "\n")

    assert "uid=0" in root("id").stdout, "A userdebug emulator with root is required"
    root("""
        set -e
        for name in NMS_FWD NMS_HOLD; do
          if iptables -S "$name" >/dev/null 2>&1; then echo 'Existing NMS route: aborting'; exit 1; fi
        done
        if ip -4 rule show | grep -qE '^(12500|12501):'; then exit 1; fi
        if ip -4 route show table 1500 | grep -q .; then exit 1; fi
        if ip -4 route show table 1501 | grep -q .; then exit 1; fi
        for iface in nmsqaap0 nmsqavpn0; do
          if ip link show "$iface" >/dev/null 2>&1; then exit 1; fi
        done
        for path in /data/local/tmp/nms314-qa.yml /data/local/tmp/nms314-qa-hev /data/local/tmp/nms314-qa-hev.log; do
          if [ -e "$path" ]; then exit 1; fi
        done
    """)

    project = Path(__file__).resolve().parent.parent
    classes = project / "app/build/intermediates/built_in_kotlinc/modernDebug/compileModernDebugKotlin/classes"
    stdlib = sorted(p for p in (Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib").glob("*/*/*.jar")
                    if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
    assert classes.joinpath("com/netmode/switcher/TetherScripts.class").exists(), "Build modernDebug first"
    assert stdlib, "Kotlin standard library was not found in Gradle's cache"
    bundled_jdk = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    jdk = Path(os.environ.get("JAVA_HOME", str(bundled_jdk)))
    javac = str(jdk / "bin/javac") if (jdk / "bin/javac").exists() else shutil.which("javac")
    java = str(jdk / "bin/java") if (jdk / "bin/java").exists() else shutil.which("java")
    classpath = os.pathsep.join(map(str, [output, classes, stdlib[-1]]))
    source = output / "RouteExport.java"
    source.write_text('''
import java.nio.file.*;
import com.netmode.switcher.*;
public class RouteExport {
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    TetherScripts s = TetherScripts.INSTANCE;
    TetherPath vpn = new TetherPath("nmsqaap0", "nmsqavpn0", "1501", true, "1.1.1.1");
    TetherPath proxy = new TetherPath("nmsqaap0", "nmsqavpn0", "1500", false, "198.18.0.2");
    String[][] scripts = {
      {"hold", s.hold("nmsqaap0")}, {"block", s.block("nmsqaap0")},
      {"vpn-open", s.open(vpn)}, {"vpn-verify", s.verify(vpn)},
      {"proxy-open", s.open(proxy)}, {"proxy-verify", s.verify(proxy)},
      {"clear", s.clearRoute()}, {"release", s.clearHold()}
    };
    for (String[] script : scripts)
      Files.writeString(dir.resolve(script[0]+".sh"), s.checked(script[1], "@PASS"));
    String config = "tunnel:\\n  name: nmsqatun0\\n  mtu: 8500\\n  ipv4: 198.18.0.1\\n" +
      "socks5:\\n  address: 127.0.0.1\\n  port: 9\\n  username: " + TetherScriptsKt.hevYamlString("qa'user") +
      "\\n  password: " + TetherScriptsKt.hevYamlString("quote'\\\"\\nEOF\\n$(id)\\\\") + "\\n";
    Files.writeString(dir.resolve("config.yml"), config);
    Files.writeString(dir.resolve("config-write.sh"), s.checked(s.privateConfig("/data/local/tmp/nms314-qa.yml", config), "@PASS"));
  }
}
''')
    subprocess.run([javac, "-cp", classpath, str(source)], check=True, timeout=30)
    subprocess.run([java, "-cp", classpath, "RouteExport", str(output)], check=True, timeout=30)

    results = []

    def passed(message):
        results.append("PASS " + message)
        print(results[-1], flush=True)
        (output / "results.txt").write_text("\n".join(results) + "\n")

    def script(name, success=True):
        result = root((output / (name + ".sh")).read_text(), check=False)
        ok = result.returncode == 0 and "@PASS" in result.stdout.splitlines()
        assert ok == success, f"{name}: expected {success}, got {result.stdout} {result.stderr}"

    read_v6 = "for f in /proc/sys/net/ipv6/conf/*/disable_ipv6; do echo \"$f=$(cat \"$f\")\"; done"
    before_v6 = root(read_v6).stdout
    before_local = root("iptables -S INPUT; iptables -S OUTPUT; ip6tables -S INPUT; ip6tables -S OUTPUT").stdout
    try:
        root("set -e\nip link add nmsqaap0 type dummy\nip link set nmsqaap0 up\n"
             "ip link add nmsqavpn0 type dummy\nip link set nmsqavpn0 up")
        script("hold")
        script("block")
        script("vpn-open")
        root("set -e\nip -4 route add default dev nmsqavpn0 table 1501\n"
             "ip -4 rule add pref 12500 iif nmsqaap0 lookup 1501")
        script("vpn-verify")
        passed("normal VPN route, NAT, MSS, DNS and unreachable guard verified by Android")
        root("iptables -w 5 -t nat -D NMS_DNS -i nmsqaap0 -p udp --dport 53 -j DNAT --to-destination 1.1.1.1")
        script("vpn-verify", success=False)
        passed("missing DNS rule is rejected")
        root("iptables -w 5 -t nat -A NMS_DNS -i nmsqaap0 -p udp --dport 53 -j DNAT --to-destination 1.1.1.1")
        root("iptables -w 5 -I FORWARD 1 -j ACCEPT")
        script("vpn-verify", success=False)
        root("iptables -w 5 -D FORWARD -j ACCEPT")
        passed("an ACCEPT ahead of the route guard is rejected")
        script("clear")
        root("set -e\nip -4 route flush table 1501\niptables -w 5 -C FORWARD -j NMS_HOLD\n"
             "ip6tables -w 5 -C FORWARD -j NMS_HOLD6")
        passed("IPv4 and IPv6 hold guards survive route teardown")
        script("block")
        script("proxy-open")
        root("set -e\nip -4 route add default dev nmsqavpn0 table 1500\n"
             "ip -4 rule add pref 12500 iif nmsqaap0 lookup 1500")
        script("proxy-verify")
        script("release")
        script("proxy-verify")
        passed("proxy route verifies after releasing temporary guards")
        script("config-write")
        assert root("cat /data/local/tmp/nms314-qa.yml").stdout == (output / "config.yml").read_text()
        passed("quoted and multiline proxy credentials survive Android shell transfer as data")
        if args.native_hev:
            assert device("shell", "getprop", "ro.product.cpu.abi").stdout.strip() == "arm64-v8a"
            device("push", str(project / "app/src/main/jniLibs/arm64-v8a/libhevtun.so"), "/data/local/tmp/nms314-qa-hev")
            root("""
                set -e
                chmod 700 /data/local/tmp/nms314-qa-hev
                /data/local/tmp/nms314-qa-hev /data/local/tmp/nms314-qa.yml > /data/local/tmp/nms314-qa-hev.log 2>&1 &
                p=$!
                trap 'kill "$p" 2>/dev/null || true; wait "$p" 2>/dev/null || true' EXIT
                sleep 1
                kill -0 "$p"
                ip link show dev nmsqatun0
            """)
            passed("bundled native HEV parses escaped YAML and creates its test TUN")
    finally:
        # These resources were confirmed absent before the test; leave all phone settings alone.
        root((output / "clear.sh").read_text() + "\n" + (output / "release.sh").read_text() +
             "\nip -4 route flush table 1501\nip link del nmsqaap0\nip link del nmsqavpn0\n" +
             "rm -f /data/local/tmp/nms314-qa.yml /data/local/tmp/nms314-qa-hev /data/local/tmp/nms314-qa-hev.log", check=False)
    after_v6 = root(read_v6).stdout
    after_local = root("iptables -S INPUT; iptables -S OUTPUT; ip6tables -S INPUT; ip6tables -S OUTPUT").stdout
    assert before_v6 == after_v6, "Phone IPv6 settings changed"
    assert before_local == after_local, "Phone INPUT/OUTPUT rules changed"
    passed("phone IPv6 sysctls and INPUT/OUTPUT rules remain unchanged")
    print(f"Evidence: {output}")


if __name__ == "__main__":
    main()
