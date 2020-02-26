(ns backup
  (:require [crux.backup :as backup]
            [crux.api :as api]))

(println "backup script")
(def opts
  {:crux.node/topology ['crux.standalone/topology
                        'crux.kv.rocksdb/kv-store]
   :crux.standalone/event-log-dir "data/eventlog-1"
   :crux.kv/db-dir "data/db-dir-1"
   :backup-dir "checkpoint"})

(defn -main []
  (let [node (api/start-node opts)]
    (backup/backup opts node)
    (System/exit 0)))
