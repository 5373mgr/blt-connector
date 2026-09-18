# アーカイブ: Docker/Raspberry Pi版設計(破棄)

2026-09-18、BLT(Beat Link Trigger)を2台以上同じPro DJ Link上で動かすと既存のリンクが壊れるバグが判明し、
設計を「単一プロセスがPro DJ Linkを受信し(Receiver)、別チャンネルで複数VJへ中継配信する(Sender)」という
Win/Mac対応GUIアプリ構成に作り直すことになったため、このディレクトリ以下は破棄した。

内容は参照用にそのまま残している(NIC選択GUIの作り方、Overlay FS対策、systemd自動起動などは
将来別の文脈で再利用できる可能性があるため)。**新しい実装では使わない。**

元の要件定義は [blt-vj-broadcast-handoff.md](blt-vj-broadcast-handoff.md) を参照。
