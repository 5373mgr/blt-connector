;; BLTの Triggers ウィンドウ → File → Editors → Global Shutdown Expression に貼り付けるコード。
;; global-setup.clj で開始したスケジューラ/ソケットの後始末を行う。

(when-let [task (:vj-broadcast-task @globals)]
  (.cancel task false))
(when-let [scheduler (:vj-broadcast-scheduler @globals)]
  (.shutdownNow scheduler))
(when-let [socket (:vj-broadcast-socket @globals)]
  (.close socket))
