"""netconfig: CDJ側/VJ出力側NICを選択するだけの最小Web GUI。

- /sys/class/net からインターフェース一覧(名前・MAC・IPv4・リンク状態)を取得して表示する
- 選択結果を /config/network.json に保存する(反映は手動のコンテナ再起動)
- BLT/Carabiner本体には一切関与しない、疎結合な独立コンテナ
"""
import json
import os
import subprocess
from pathlib import Path

from flask import Flask, redirect, render_template, request, url_for

SYS_CLASS_NET = Path("/sys/class/net")
CONFIG_PATH = Path(os.environ.get("NETCONFIG_CONFIG_PATH", "/config/network.json"))

app = Flask(__name__)


def _read_text(path: Path) -> str:
    try:
        return path.read_text().strip()
    except OSError:
        return ""


def _ipv4_of(ifname: str) -> str:
    try:
        out = subprocess.run(
            ["ip", "-4", "-o", "addr", "show", ifname],
            capture_output=True, text=True, timeout=2, check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return ""
    # 例: "2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 ..."
    for line in out.splitlines():
        parts = line.split()
        if "inet" in parts:
            idx = parts.index("inet")
            if idx + 1 < len(parts):
                return parts[idx + 1].split("/")[0]
    return ""


def list_interfaces():
    if not SYS_CLASS_NET.is_dir():
        return []
    interfaces = []
    for entry in sorted(SYS_CLASS_NET.iterdir()):
        name = entry.name
        if name == "lo":
            continue
        interfaces.append({
            "name": name,
            "mac": _read_text(entry / "address"),
            "ipv4": _ipv4_of(name),
            "operstate": _read_text(entry / "operstate") or "unknown",
        })
    return interfaces


def load_saved_config():
    if not CONFIG_PATH.exists():
        return {}
    try:
        return json.loads(CONFIG_PATH.read_text())
    except (OSError, json.JSONDecodeError):
        return {}


def save_config(cdj_interface: str, vj_interface: str):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps({
        "cdj_interface": cdj_interface,
        "vj_interface": vj_interface,
    }, ensure_ascii=False, indent=2))


@app.route("/", methods=["GET"])
def index():
    saved = load_saved_config()
    return render_template(
        "index.html",
        interfaces=list_interfaces(),
        saved=saved,
        saved_ok=request.args.get("saved") == "1",
        error=request.args.get("error"),
    )


@app.route("/save", methods=["POST"])
def save():
    cdj_interface = request.form.get("cdj_interface", "")
    vj_interface = request.form.get("vj_interface", "")

    if not cdj_interface or not vj_interface:
        return redirect(url_for("index", error="both"))
    if cdj_interface == vj_interface:
        return redirect(url_for("index", error="same"))

    save_config(cdj_interface, vj_interface)
    return redirect(url_for("index", saved="1"))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
