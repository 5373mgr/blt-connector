#!/usr/bin/env python3
"""
eth0上にこのPi自身(dnsmasq, server identifier=192.168.50.1)以外のDHCPサーバーが
存在するかを、DHCPDISCOVERをブロードキャストして届いたDHCPOFFERの送信元/server-identifierを
見て判定する。root権限が必要(DHCPクライアントポート68へのbind)。

出力: 標準出力に1行、"FOREIGN_DHCP=true" または "FOREIGN_DHCP=false" のみを出す
(呼び出し側でのパースを単純にするため、他の情報は標準エラーへ出す)。
"""
import socket
import struct
import sys
import time

IFACE = sys.argv[1] if len(sys.argv) > 1 else "eth0"
OWN_SERVER_IP = sys.argv[2] if len(sys.argv) > 2 else "192.168.50.1"
TIMEOUT_SECONDS = 5.0

MAGIC_COOKIE = bytes([99, 130, 83, 99])


def build_discover(xid: int, mac: bytes) -> bytes:
    # BOOTP/DHCP header (RFC 2131)
    op = 1  # BOOTREQUEST
    htype = 1  # Ethernet
    hlen = 6
    hops = 0
    secs = 0
    flags = 0x8000  # broadcast flag: サーバーからの応答もブロードキャストで返してもらう
    ciaddr = 0
    yiaddr = 0
    siaddr = 0
    giaddr = 0
    chaddr = mac + b"\x00" * 10  # 16 bytes
    sname = b"\x00" * 64
    file_ = b"\x00" * 128

    header = struct.pack(
        "!BBBBIHHIIII16s64s128s",
        op, htype, hlen, hops, xid, secs, flags,
        ciaddr, yiaddr, siaddr, giaddr, chaddr, sname, file_,
    )

    options = MAGIC_COOKIE
    options += bytes([53, 1, 1])  # DHCP Message Type = DHCPDISCOVER
    options += bytes([55, 3, 1, 3, 6])  # Parameter Request List: subnet/router/dns(参考情報、必須ではない)
    options += bytes([255])  # End

    return header + options


def parse_offer(data: bytes):
    if len(data) < 240:
        return None
    yiaddr = socket.inet_ntoa(data[16:20])
    opts = data[240:]
    server_id = None
    msg_type = None
    i = 0
    while i < len(opts):
        code = opts[i]
        if code == 255:
            break
        if code == 0:
            i += 1
            continue
        if i + 1 >= len(opts):
            break
        length = opts[i + 1]
        value = opts[i + 2:i + 2 + length]
        if code == 53 and len(value) == 1:
            msg_type = value[0]
        elif code == 54 and len(value) == 4:
            server_id = socket.inet_ntoa(value)
        i += 2 + length
    return {"yiaddr": yiaddr, "server_id": server_id, "msg_type": msg_type}


def get_iface_mac(iface: str) -> bytes:
    with open(f"/sys/class/net/{iface}/address") as f:
        text = f.read().strip()
    return bytes(int(b, 16) for b in text.split(":"))


def main() -> int:
    xid = int(time.time()) & 0xFFFFFFFF
    # DHCPサーバーはDHCPOFFERをchaddr宛のEthernetユニキャストフレームとして送ってくることが多く、
    # 実在しないMACアドレスを使うと自分のNIC(非プロミスキャスモード)がフレームを受信前に破棄してしまう
    # (実機で確認済み、dnsmasq側はDHCPOFFERを送っているのに本スクリプト側で一切受信できなかった)。
    # そのため実際のeth0のMACアドレスをそのまま使う(DHCPREQUESTまでは進めないので実際にリースが
    # 確定することはない)。
    mac = get_iface_mac(IFACE)

    # DHCPサーバーは応答先を送信元ポート(68=bootpc)で判断することが多いため、送受信を同一の
    # ソケット(ローカルポート68にbind)で行う。ポートを分けていた旧実装ではdnsmasqからの応答が
    # 一切届かなかった(実機で確認済み)。
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, IFACE.encode())
    except (AttributeError, OSError) as e:
        print(f"SO_BINDTODEVICE failed: {e}", file=sys.stderr)
    sock.bind(("", 68))

    packet = build_discover(xid, mac)
    sock.sendto(packet, ("255.255.255.255", 67))
    print(f"sent DHCPDISCOVER xid={xid:#x}", file=sys.stderr)

    foreign_found = False
    deadline = time.time() + TIMEOUT_SECONDS
    while time.time() < deadline:
        remaining = deadline - time.time()
        if remaining <= 0:
            break
        sock.settimeout(remaining)
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            break
        offer = parse_offer(data)
        if offer is None:
            continue
        print(f"received from {addr}: {offer}", file=sys.stderr)
        if offer["msg_type"] == 2:  # DHCPOFFER
            server_id = offer["server_id"] or addr[0]
            if server_id != OWN_SERVER_IP:
                foreign_found = True

    sock.close()

    print(f"FOREIGN_DHCP={'true' if foreign_found else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
