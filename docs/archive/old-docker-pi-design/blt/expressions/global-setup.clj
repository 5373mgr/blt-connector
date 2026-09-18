;; BLTの Triggers ウィンドウ → File → Editors → Global Setup Expression に貼り付けるコード。
;;
;; 目的: デッキ1〜4の曲名・アーティスト・再生位置を10Hzで読み取り、
;;       netconfigが書き出した /config/network.json の "vj_interface" 宛にJSONをUDPブロードキャストする。
;;
;; 注意(未検証):
;;  - beat-link の TrackMetadata / TimeFinder / VirtualCdj のAPI呼び出しは実機のCDJ+BLTで
;;    動作確認していません。デバッグ用VNC(handoffのXvfb+x11vnc手順)経由でBLTのGUIを開き、
;;    実際のCDJを接続した状態でこの式を貼り付けて動作確認してから .blt として保存すること。
;;  - JSONは外部ライブラリに依存させず自前の最小エンコーダで組み立てている
;;    (BLTのクラスパスにどのJSONライブラリが乗っているか前提にしないため)。

(import '[org.deepsymmetry.beatlink VirtualCdj]
        '[org.deepsymmetry.beatlink.data MetadataFinder TimeFinder]
        '[java.net DatagramSocket DatagramPacket NetworkInterface InetAddress]
        '[java.nio.charset StandardCharsets]
        '[java.util.concurrent Executors TimeUnit])

(def vj-broadcast-port 51000)
(def network-config-path "/config/network.json")

;; ---- 最小JSONエンコーダ(文字列エスケープ + マップ/ベクタ/数値/真偽値/nilのみ対応) ----
(defn- json-escape-string [s]
  (str "\""
       (clojure.string/escape s
         {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"})
       "\""))

(defn- json-value [v]
  (cond
    (nil? v) "null"
    (string? v) (json-escape-string v)
    (boolean? v) (str v)
    (number? v) (str v)
    (map? v) (str "{"
                   (clojure.string/join ","
                     (map (fn [[k val]] (str (json-escape-string (name k)) ":" (json-value val))) v))
                   "}")
    (sequential? v) (str "[" (clojure.string/join "," (map json-value v)) "]")
    :else (json-escape-string (str v))))

;; ---- netconfigの設定読み込み(ファイルが無い/壊れている場合はnilを返し送信をスキップ) ----
(defn- read-network-config []
  (try
    (let [text (slurp network-config-path)]
      ;; 簡易JSONパーサは持たないため、シンプルな正規表現で "vj_interface" の値だけ取り出す
      (when-let [m (re-find #"\"vj_interface\"\s*:\s*\"([^\"]+)\"" text)]
        {:vj-interface (second m)}))
    (catch Exception e
      (log/warn e "network.jsonの読み込みに失敗しました")
      nil)))

(defn- broadcast-address-for [ifname]
  (try
    (when-let [ni (NetworkInterface/getByName ifname)]
      (some->> (.getInterfaceAddresses ni)
               (keep #(.getBroadcast %))
               first))
    (catch Exception e
      (log/warn e (str "インターフェース " ifname " のブロードキャストアドレス取得に失敗"))
      nil)))

;; ---- beat-linkのサブシステムを起動(未起動なら開始。BLT本体が既に起動済みなら何もしない想定) ----
(defn- ensure-started []
  (try (.start (MetadataFinder/getInstance)) (catch Exception _ nil))
  (try (.start (TimeFinder/getInstance)) (catch Exception _ nil)))

(defn- deck-info [player-number]
  (try
    (let [vcdj (VirtualCdj/getInstance)
          status (.getLatestStatusFor vcdj player-number)]
      (when status
        (let [metadata (.getLatestMetadataFor (MetadataFinder/getInstance) player-number)
              position (.getTimeFor (TimeFinder/getInstance) player-number)
              artist (when (and metadata (.getArtist metadata)) (.getLabel (.getArtist metadata)))]
          {:id player-number
           :title (when metadata (.getTitle metadata))
           :artist artist
           :position_ms (when (and position (>= position 0)) position)
           :duration_ms (when metadata (* 1000 (.getDuration metadata)))
           :playing (.isPlaying status)})))
    (catch Exception e
      (log/warn e (str "player " player-number " の情報取得に失敗"))
      nil)))

(defn- broadcast-tick [socket]
  (when-let [cfg (read-network-config)]
    (when-let [addr (broadcast-address-for (:vj-interface cfg))]
      (let [decks (->> [1 2 3 4] (map deck-info) (remove nil?) vec)
            payload (json-value {:decks decks})
            payload-bytes (.getBytes ^String payload StandardCharsets/UTF_8)
            packet (DatagramPacket. payload-bytes (alength payload-bytes) ^InetAddress addr (int vj-broadcast-port))]
        (.send ^DatagramSocket socket packet)))))

(ensure-started)

(def vj-broadcast-socket (doto (DatagramSocket.) (.setBroadcast true)))
(def vj-broadcast-scheduler (Executors/newSingleThreadScheduledExecutor))
(def vj-broadcast-task
  (.scheduleAtFixedRate vj-broadcast-scheduler
                         (fn [] (broadcast-tick vj-broadcast-socket))
                         0 100 TimeUnit/MILLISECONDS))

;; グローバル変数として保持し、Global Shutdown Expression から参照して後始末する
(swap! globals assoc
       :vj-broadcast-scheduler vj-broadcast-scheduler
       :vj-broadcast-task vj-broadcast-task
       :vj-broadcast-socket vj-broadcast-socket)
