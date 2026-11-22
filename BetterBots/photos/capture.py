from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import struct
import subprocess
import threading
import time
import uuid
from pathlib import Path

from minecraft_launcher_lib.command import get_minecraft_command

ROOT = Path(__file__).resolve().parent
SERVER = ROOT / "server"
CLIENT = ROOT / "client"
OUTPUT = ROOT / "screenshots"
VERSION = "26.3"
USERNAME = "PhotoClient"
HOST = "127.0.0.1"
PORT = 25567
FLOOR = -60

class Process:
    def __init__(self, command, cwd, log):
        self.log = ROOT / log
        self.lines = []
        self.child = subprocess.Popen(
            command, cwd=cwd, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        with self.log.open("w") as output:
            for line in self.child.stdout:
                self.lines.append(line)
                output.write(line)
                output.flush()
                if self.log.name == "server.log" or re.search(r"ERROR|Connecting|joined", line):
                    print(line.rstrip(), flush=True)

    def send(self, command):
        if self.child.poll() is not None:
            raise RuntimeError(f"{self.log.name} exited with code {self.child.returncode}")
        self.child.stdin.write(command + "\n")
        self.child.stdin.flush()

    def wait_for(self, expression, seconds, start=0):
        pattern = re.compile(expression)
        return wait_for(expression, lambda: next(
            (line for line in self.lines[start:] if pattern.search(line)), None
        ), seconds, self)

    def close(self, console_command=None):
        if self.child.poll() is None:
            if console_command is not None:
                self.send(console_command)
            else:
                self.child.terminate()
            try:
                self.child.wait(timeout=35)
            except subprocess.TimeoutExpired:
                self.child.kill()
                self.child.wait(timeout=8)
        self.reader.join(timeout=3)


def wait_for(description, condition, seconds, process=None):
    start = time.monotonic()
    while time.monotonic() - start < seconds:
        result = condition()
        if result:
            return result
        if process is not None and process.child.poll() is not None:
            raise RuntimeError(f"{process.log.name} exited before {description}\n" + "".join(process.lines[-15:]))
        time.sleep(0.2)
    raise TimeoutError(f"Timed out waiting for {description}")


def execute(*args, **kwargs):
    return subprocess.run(args, text=True, check=True, timeout=10, **kwargs)


def configure(offline_uuid):
    flat = {
        "biome": "minecraft:plains",
        "layers": [
            {"height": 1, "block": "minecraft:bedrock"},
            {"height": 2, "block": "minecraft:dirt"},
            {"height": 1, "block": "minecraft:grass_block"},
        ],
    }
    (SERVER / "eula.txt").write_text("eula=true\n")
    (SERVER / "whitelist.json").write_text(json.dumps([{"uuid": str(offline_uuid), "name": USERNAME}]))
    (SERVER / "ops.json").write_text(json.dumps([
        {"uuid": str(offline_uuid), "name": USERNAME, "level": 4, "bypassesPlayerLimit": False}
    ]))
    (SERVER / "server.properties").write_text("\n".join([
        f"server-ip={HOST}", f"server-port={PORT}", "online-mode=false",
        "enforce-secure-profile=false", "white-list=true", "enforce-whitelist=true",
        "level-type=minecraft:flat", "generator-settings=" + json.dumps(flat),
        "spawn-protection=0", "max-players=20", "view-distance=6", "simulation-distance=5",
        "spawn-monsters=false", "generate-structures=false", "gamemode=creative",
        "motd=BetterBots visual smoke test",
    ]) + "\n")
    (CLIENT / "options.txt").write_text("\n".join([
        "enableVsync:false", "maxFps:30", "renderDistance:6", "simulationDistance:5",
        "fullscreen:false", "guiScale:3", "cloudStatus:off", "tutorialStep:none",
        "joinedFirstServer:true", "showSubtitles:false", "pauseOnLostFocus:false",
        "soundCategory_master:0.0", "chatDelay:0.0", "fov:0.0",
    ]) + "\n")


def start_client(offline_uuid):
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    command = get_minecraft_command(VERSION, CLIENT, {
        "username": USERNAME, "uuid": offline_uuid.hex, "token": "0",
        "executablePath": java, "gameDirectory": str(CLIENT),
        "jvmArguments": ["-Xms512M", "-Xmx2700M"],
        "customResolution": True, "resolutionWidth": "1280", "resolutionHeight": "800",
        "quickPlayPath": str(CLIENT / "quickplay.json"),
        "quickPlayMultiplayer": f"{HOST}:{PORT}",
    })
    return Process(command, CLIENT, "client.log")


def build_courtyard(server):
    for command in [
        "gamerule minecraft:advance_time false",
        "gamerule minecraft:advance_weather false",
        "gamerule minecraft:spawn_mobs false",
        "time set 2400",
        "weather clear",
        f"gamemode spectator {USERNAME}",
        f"tp {USERNAME} 0 -50 14 180 30",
        "forceload add -16 -16 15 15",
        "fill -14 -61 -13 14 -61 13 minecraft:polished_andesite",
        "fill -13 -61 -12 13 -61 12 minecraft:stone_bricks",
        "fill -12 -61 -11 12 -61 11 minecraft:smooth_stone",
        "fill -1 -61 -13 1 -61 13 minecraft:polished_andesite",
        "fill -14 -61 -1 14 -61 1 minecraft:polished_andesite",
        "fill -9 -61 4 -6 -61 7 minecraft:spruce_planks",
        "fill 7 -61 4 10 -61 7 minecraft:spruce_planks",
    ]:
        server.send(command)
    for x, z in [(-12, -11), (12, -11), (-12, 11), (12, 11), (-6, -10), (6, -10)]:
        server.send(f"fill {x} -60 {z} {x} -58 {z} minecraft:stone_brick_wall")
        server.send(f"setblock {x} -57 {z} minecraft:lantern")
    for x in (-14, 14):
        server.send(f"fill {x} -60 -13 {x} -60 13 minecraft:spruce_fence")
    for x, z in [(-10, -8), (9, -8), (-10, 8), (11, 8)]:
        server.send(f"setblock {x} -60 {z} minecraft:decorated_pot")
        server.send(f"setblock {x} -59 {z} minecraft:potted_fern")
    server.send("fill -4 -60 -11 4 -60 -11 minecraft:spruce_planks")
    server.send("fill -4 -59 -11 4 -59 -11 minecraft:spruce_slab")
    server.send("setblock -4 -58 -11 minecraft:lantern")
    server.send("setblock 4 -58 -11 minecraft:lantern")


def add_bot(server, name, style, x, z):
    index = len(server.lines)
    server.send(f"setworldspawn {x} {FLOOR} {z}")
    server.send(f"bb create {name} {style}")
    server.wait_for(r"Added " + name, 12, index)
    server.send(f"bb stay {name}")
    time.sleep(0.3)


def camera(server, x, y, z, target_x, target_y, target_z):
    delta_x, delta_z = target_x - x, target_z - z
    yaw = math.degrees(math.atan2(-delta_x, delta_z))
    pitch = math.degrees(math.atan2(y + 1.62 - target_y, math.hypot(delta_x, delta_z)))
    server.send(f"tp {USERNAME} {x:.2f} {y:.2f} {z:.2f} {yaw:.1f} {pitch:.1f}")


def xdotool(*args):
    return execute("xdotool", *map(str, args), capture_output=True).stdout.strip()


def focus_window():
    output = subprocess.run(
        ["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
        check=False, capture_output=True, text=True,
    )
    if output.returncode:
        return None
    return output.stdout.splitlines()[-1]


def native_screenshot(index, label, window, keys=None):
    xdotool("windowfocus", "--sync", window)
    prior = set((CLIENT / "screenshots").glob("*.png"))
    if keys:
        xdotool("keydown", keys)
    try:
        time.sleep(0.3)
        xdotool("key", "F2")
        path = wait_for("Minecraft F2 capture " + label, lambda: next(iter(
            set((CLIENT / "screenshots").glob("*.png")) - prior
        ), None), 12)
        wait_for("complete PNG", lambda: path.read_bytes()[-12:] == b"\x00\x00\x00\x00IEND\xaeB\x60\x82", 8)
        target = OUTPUT / f"{index:02d}-{label}.png"
        shutil.copyfile(path, target)
        with target.open("rb") as handle:
            header = handle.read(24)
        assert header[:8] == b"\x89PNG\r\n\x1a\n"
        width, height = struct.unpack(">II", header[16:24])
        assert width >= 1000 and height >= 700, (target.name, width, height)
        print(f"CAPTURED {target.name} {width}x{height} ({target.stat().st_size} bytes)", flush=True)
        time.sleep(1.1)
    finally:
        if keys:
            xdotool("keyup", keys)


def capture(server, client, window):
    OUTPUT.mkdir(exist_ok=True)
    build_courtyard(server)
    for name, style, x, z in [
        ("TorchFox", "miner", 3, 2),
        ("RiverQuill", "fisher", -6, 3),
        ("MossLantern", "explorer", 7, -4),
        ("CopperVale", "builder", -3, -6),
        ("JuniperGrove", "farmer", 9, 7),
    ]:
        add_bot(server, name, style, x, z)
    server.send("setworldspawn 0 -60 0")
    server.send("bb equip TorchFox hand diamond_pickaxe")
    server.send("bb equip TorchFox head iron_helmet")
    server.send("bb equip CopperVale hand brick")
    server.send("bb equip MossLantern offhand lantern")
    server.send("bb equip RiverQuill offhand tropical_fish")
    time.sleep(2)
    xdotool("key", "F1")

    scenes = [
        (1, "courtyard-overview", (0, -46, 19), (1, -59, 0)),
        (2, "east-side-group", (14, -54, 6), (1, -59, 1)),
        (3, "south-path", (0, -60, 12), (1, -59, 2)),
        (4, "west-side-group", (-13, -56, 2), (1, -59, 0)),
        (5, "miner-torchfox", (3.5, -60, 5.5), (3.5, -58.9, 2.5)),
        (6, "fisher-riverquill", (-5.5, -60, 6.3), (-5.5, -59.0, 3.5)),
        (7, "explorer-mosslantern", (7.5, -60, -0.5), (7.5, -59, -3.5)),
        (8, "builder-coppervale", (-2.5, -60, -2.6), (-2.5, -59, -5.5)),
        (9, "farmer-junipergrove", (9.5, -60, 10.7), (9.5, -59, 7.5)),
        (10, "miner-side-profile", (5.6, -59.7, 3.6), (3.5, -59, 2.5)),
        (11, "three-quarter-courtyard", (-10, -51, 12), (0, -59, 0)),
    ]
    for index, label, position, target in scenes:
        if client.child.poll() is not None:
            raise RuntimeError("The official client exited during the photo sequence")
        camera(server, *position, *target)
        time.sleep(0.7)
        native_screenshot(index, label, window)

    xdotool("key", "F1")
    camera(server, 2, -56, 12, 2, -59, 0)
    time.sleep(0.7)
    native_screenshot(12, "tab-list-default", window, keys="Tab")
    camera(server, 3.5, -60, 6.5, 3.5, -59, 2.5)
    time.sleep(0.65)
    start = len(server.lines)
    xdotool("key", "t")
    time.sleep(0.3)
    xdotool("type", "--delay", "22", "TorchFox, where are you?")
    xdotool("key", "Return")
    server.wait_for(r"TorchFox » i'm around", 20, start)
    xdotool("key", "t")
    time.sleep(0.3)
    native_screenshot(13, "contextual-chat", window)
    xdotool("key", "Escape")

    server.send("bb give TorchFox emerald 8")
    server.send("bb give TorchFox apple 6")
    server.send("bb give TorchFox diamond 3")
    xdotool("key", "slash")
    time.sleep(0.25)
    xdotool("type", "--delay", "20", "bb inventory TorchFox")
    xdotool("key", "Return")
    time.sleep(0.65)
    xdotool("key", "t")
    native_screenshot(14, "bot-inventory-command", window)
    xdotool("key", "Escape")
    server.send("bb drop TorchFox 3")
    server.send("bb drop TorchFox 4")
    server.send("bb drop TorchFox 5")
    camera(server, 3.5, -58.7, 6.2, 3.5, -59.8, 3.1)
    time.sleep(1.0)
    native_screenshot(15, "dropped-inventory-items", window)

    server.send(f"bb follow MossLantern {USERNAME}")
    camera(server, 9, -58.5, 7.5, 7.5, -59.0, -2.8)
    time.sleep(0.55)
    native_screenshot(16, "explorer-starts-following", window)
    camera(server, 10, -58.0, 8.7, 8.4, -59, 3.5)
    time.sleep(0.65)
    native_screenshot(17, "explorer-on-the-path", window)
    server.send("bb stay MossLantern")

    config = SERVER / "plugins" / "BetterBots" / "config.yml"
    content = config.read_text()
    assert "show-bot-tag: false" in content
    config.write_text(content.replace("show-bot-tag: false", "show-bot-tag: true"))
    index = len(server.lines)
    server.send("bb reload")
    server.wait_for(r"Settings reloaded", 8, index)
    camera(server, 2, -55, 11, 2, -59, 0)
    time.sleep(0.6)
    native_screenshot(18, "tab-list-labelled-option", window, keys="Tab")
    camera(server, 3.5, -60, 6, 3.5, -59, 2.5)
    server.send("bb say TorchFox I'll keep an eye on the courtyard.")
    time.sleep(0.6)
    native_screenshot(19, "labelled-name-and-chat", window)
    server.send("time set 11800")
    camera(server, -11, -52, 12, 1, -59, 0)
    time.sleep(0.8)
    native_screenshot(20, "sunset-over-the-courtyard", window)

    images = sorted(OUTPUT.glob("*.png"))
    assert len(images) == 20
    assert len({hashlib.sha256(image.read_bytes()).digest() for image in images}) == 20
    print("PASS all 20 distinct native Minecraft screenshots captured", flush=True)


def main():
    offline_uuid = uuid.UUID(bytes=hashlib.md5(f"OfflinePlayer:{USERNAME}".encode()).digest(), version=3)
    configure(offline_uuid)
    client = None
    server = Process(["java", "-Xms512M", "-Xmx1700M", "-jar", "paper.jar", "--nogui"], SERVER, "server.log")
    try:
        server.wait_for(r"Done \([\d.]+s\)!", 160)
        client = start_client(offline_uuid)
        window = wait_for("official Minecraft window", focus_window, 120, client)
        xdotool("windowfocus", "--sync", window)
        server.wait_for(USERNAME + r" joined the game", 150)
        print("PASS official Minecraft 26.3 client joined Paper", flush=True)
        time.sleep(5)
        capture(server, client, window)
    except BaseException:
        command = shutil.which("import")
        if command:
            subprocess.run([command, "-window", "root", str(ROOT / "diagnostic.png")], timeout=8, check=False)
        if client:
            print("".join(client.lines[-70:]), flush=True)
        raise
    finally:
        if client:
            client.close()
        server.close("stop")


if __name__ == "__main__":
    main()
