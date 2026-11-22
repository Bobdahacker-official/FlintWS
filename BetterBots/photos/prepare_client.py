from pathlib import Path

from minecraft_launcher_lib.install import install_minecraft_version

directory = Path(__file__).resolve().parent / "client"
directory.mkdir(exist_ok=True)
install_minecraft_version("26.3", directory, {"setStatus": lambda status: print(status, flush=True)})
print("Official Minecraft 26.3 client installed.", flush=True)
