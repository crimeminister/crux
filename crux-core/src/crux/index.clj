(ns ^:no-doc crux.index
  (:require [crux.codec :as c]
            [crux.db :as db]
            [crux.kv :as kv]
            [crux.memory :as mem]
            [crux.morton :as morton]
            [taoensso.nippy :as nippy])
  (:import [clojure.lang IReduceInit MapEntry Seqable Sequential]
           crux.api.IndexVersionOutOfSyncException
           [crux.codec EntityValueContentHash EntityTx]
           [crux.index BinaryJoinLayeredVirtualIndexState DocAttributeValueEntityEntityIndexState EntityHistoryRangeState EntityValueEntityPeekState NAryJoinLayeredVirtualIndexState NAryWalkState RelationIteratorsState RelationNestedIndexState SortedVirtualIndexState UnaryJoinIteratorState UnaryJoinIteratorsThunkFnState UnaryJoinIteratorsThunkState ValueEntityValuePeekState]
           [java.io Closeable DataInputStream]
           [java.util Collections Comparator Date]
           java.util.function.Supplier
           [org.agrona DirectBuffer ExpandableDirectByteBuffer]
           org.agrona.io.DirectBufferInputStream))

(set! *unchecked-math* :warn-on-boxed)

;; NOTE: A buffer returned from an kv/KvIterator can only be assumed
;; to be valid until the next call on the same iterator. In practice
;; this limitation is only for RocksJNRKv.
;; TODO: It would be nice to make this explicit somehow.

;; Indexes

(defrecord PrefixKvIterator [i ^DirectBuffer prefix]
  kv/KvIterator
  (seek [_ k]
    (when-let [k (kv/seek i k)]
      (when (mem/buffers=? k prefix (.capacity prefix))
        k)))

  (next [_]
    (when-let [k (kv/next i)]
      (when (mem/buffers=? k prefix (.capacity prefix))
        k)))

  (value [_]
    (kv/value i))

  Closeable
  (close [_]
    (.close ^Closeable i)))

(defn new-prefix-kv-iterator ^java.io.Closeable [i prefix]
  (->PrefixKvIterator i prefix))

(def ^ThreadLocal seek-buffer-tl
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_]
       (ExpandableDirectByteBuffer.)))))

;; AVE

(defn- attribute-value+placeholder [k ^ValueEntityValuePeekState peek-state]
  (let [value (.value (c/decode-avec-key->evc-from k))]
    (set! (.last-k peek-state) k)
    (set! (.value peek-state) value)
    (MapEntry/create value :crux.index.binary-placeholder/value)))

(defrecord DocAttributeValueEntityValueIndex [i ^DirectBuffer attr ^ValueEntityValuePeekState peek-state]
  db/Index
  (seek-values [this k]
    (when-let [k (->> (c/encode-avec-key-to
                       (.get seek-buffer-tl)
                       attr
                       (or k c/empty-buffer))
                      (kv/seek i))]
      (attribute-value+placeholder k peek-state)))

  (next-values [this]
    (when-let [last-k (.last-k peek-state)]
      (let [prefix-size (- (.capacity ^DirectBuffer last-k) c/id-size c/id-size)]
        (when-let [k (some->> (mem/inc-unsigned-buffer! (mem/limit-buffer (mem/copy-buffer last-k prefix-size (.get seek-buffer-tl)) prefix-size))
                              (kv/seek i))]
          (attribute-value+placeholder k peek-state))))))

(defn new-doc-attribute-value-entity-value-index [snapshot attr]
  (let [attr (c/->id-buffer attr)
        prefix (c/encode-avec-key-to nil attr)]
    (->DocAttributeValueEntityValueIndex (new-prefix-kv-iterator (kv/new-iterator snapshot) prefix) attr (ValueEntityValuePeekState. nil nil))))

(defn- attribute-value-entity-entity+value [snapshot i ^DirectBuffer current-k attr value entity-resolver-fn peek-eb ^DocAttributeValueEntityEntityIndexState peek-state]
  (loop [k current-k]
    (let [limit (- (.capacity k) c/id-size)]
      (set! (.peek peek-state) (mem/inc-unsigned-buffer! (mem/limit-buffer (mem/copy-buffer k limit peek-eb) limit))))
    (or (let [eid (.eid (c/decode-avec-key->evc-from k))
              eid-buffer (c/->id-buffer eid)
              ^EntityTx entity-tx (entity-resolver-fn eid-buffer)]
          (when entity-tx
            (let [eid-buffer (c/->id-buffer (.eid entity-tx))
                  version-k (c/encode-avec-key-to
                             (.get seek-buffer-tl)
                             attr
                             value
                             eid-buffer
                             (c/->id-buffer (.content-hash entity-tx)))]
              (when (kv/get-value snapshot version-k)
                (MapEntry/create eid-buffer entity-tx)))))
        (when-let [k (some->> (.peek peek-state) (kv/seek i))]
          (recur k)))))

(defrecord ValueAndPrefixIterator [value prefix-iterator])

(defn- attribute-value-value+prefix-iterator ^crux.index.ValueAndPrefixIterator [i ^DocAttributeValueEntityValueIndex value-entity-value-idx attr prefix-eb]
  (let [value (.value ^ValueEntityValuePeekState (.peek-state value-entity-value-idx))
        prefix (c/encode-avec-key-to prefix-eb attr value)]
    (ValueAndPrefixIterator. value (new-prefix-kv-iterator i prefix))))

(defrecord DocAttributeValueEntityEntityIndex [snapshot i ^DirectBuffer attr value-entity-value-idx entity-resolver-fn prefix-eb peek-eb ^DocAttributeValueEntityEntityIndexState peek-state]
  db/Index
  (seek-values [this k]
    (when (c/valid-id? k)
      (let [value+prefix-iterator (attribute-value-value+prefix-iterator i value-entity-value-idx attr prefix-eb)
            value (.value value+prefix-iterator)
            i (.prefix-iterator value+prefix-iterator)]
        (when-let [k (->> (c/encode-avec-key-to
                           (.get seek-buffer-tl)
                           attr
                           value
                           (or k c/empty-buffer))
                          (kv/seek i))]
          (attribute-value-entity-entity+value snapshot i k attr value entity-resolver-fn peek-eb peek-state)))))

  (next-values [this]
    (let [value+prefix-iterator (attribute-value-value+prefix-iterator i value-entity-value-idx attr prefix-eb)
          value (.value value+prefix-iterator)
          i (.prefix-iterator value+prefix-iterator)]
      (when-let [k (some->> (.peek peek-state) (kv/seek i))]
        (attribute-value-entity-entity+value snapshot i k attr value entity-resolver-fn peek-eb peek-state)))))

(defn new-doc-attribute-value-entity-entity-index [snapshot attr value-entity-value-idx entity-resolver-fn]
  (->DocAttributeValueEntityEntityIndex snapshot (kv/new-iterator snapshot) (c/->id-buffer attr) value-entity-value-idx entity-resolver-fn
                                        (ExpandableDirectByteBuffer.) (ExpandableDirectByteBuffer.)
                                        (DocAttributeValueEntityEntityIndexState. nil)))

;; AEV

(defn- attribute-entity+placeholder [k attr entity-resolver-fn ^EntityValueEntityPeekState peek-state]
  (let [eid (.eid (c/decode-aecv-key->evc-from k))
        eid-buffer (c/->id-buffer eid)
        ^EntityTx entity-tx (entity-resolver-fn eid-buffer)]
    (set! (.last-k peek-state) k)
    (set! (.entity-tx peek-state) entity-tx)
    (if entity-tx
      (MapEntry/create (c/->id-buffer (.eid entity-tx)) :crux.index.binary-placeholder/entity)
      ::deleted-entity)))

(defrecord DocAttributeEntityValueEntityIndex [i ^DirectBuffer attr entity-resolver-fn ^EntityValueEntityPeekState peek-state]
  db/Index
  (seek-values [this k]
    (when (c/valid-id? k)
      (when-let [k (->> (c/encode-aecv-key-to
                         (.get seek-buffer-tl)
                         attr
                         (or k c/empty-buffer))
                        (kv/seek i))]
        (let [placeholder (attribute-entity+placeholder k attr entity-resolver-fn peek-state)]
          (if (= ::deleted-entity placeholder)
            (db/next-values this)
            placeholder)))))

  (next-values [this]
    (when-let [last-k (.last-k peek-state)]
      (let [prefix-size (+ c/index-id-size c/id-size c/id-size)]
        (when-let [k (some->> (mem/inc-unsigned-buffer! (mem/limit-buffer (mem/copy-buffer last-k prefix-size (.get seek-buffer-tl)) prefix-size))
                              (kv/seek i))]
          (let [placeholder (attribute-entity+placeholder k attr entity-resolver-fn peek-state)]
            (if (= ::deleted-entity placeholder)
              (recur)
              placeholder)))))))

(defn new-doc-attribute-entity-value-entity-index [snapshot attr entity-resolver-fn]
  (let [attr (c/->id-buffer attr)
        prefix (c/encode-aecv-key-to nil attr)]
    (->DocAttributeEntityValueEntityIndex (new-prefix-kv-iterator (kv/new-iterator snapshot) prefix) attr entity-resolver-fn
                                          (EntityValueEntityPeekState. nil nil))))

(defrecord EntityTxAndPrefixIterator [entity-tx prefix-iterator])

(defn- attribute-value-entity-tx+prefix-iterator ^crux.index.EntityTxAndPrefixIterator [i ^DocAttributeEntityValueEntityIndex entity-value-entity-idx attr prefix-eb]
  (let [entity-tx ^EntityTx (.entity-tx ^EntityValueEntityPeekState (.peek-state entity-value-entity-idx))
        prefix (c/encode-aecv-key-to prefix-eb attr (c/->id-buffer (.eid entity-tx)) (c/->id-buffer (.content-hash entity-tx)))]
    (EntityTxAndPrefixIterator. entity-tx (new-prefix-kv-iterator i prefix))))

(defrecord DocAttributeEntityValueValueIndex [i ^DirectBuffer attr entity-value-entity-idx prefix-eb]
  db/Index
  (seek-values [this k]
    (let [entity-tx+prefix-iterator (attribute-value-entity-tx+prefix-iterator i entity-value-entity-idx attr prefix-eb)
          entity-tx ^EntityTx (.entity-tx entity-tx+prefix-iterator)
          i (.prefix-iterator entity-tx+prefix-iterator)]
      (when-let [k (->> (c/encode-aecv-key-to
                         (.get seek-buffer-tl)
                         attr
                         (c/->id-buffer (.eid entity-tx))
                         (c/->id-buffer (.content-hash entity-tx))
                         (or k c/empty-buffer))
                        (kv/seek i))]
        (MapEntry/create (mem/copy-buffer (.value (c/decode-aecv-key->evc-from k)))
                         entity-tx))))

  (next-values [this]
    (let [entity-tx+prefix-iterator (attribute-value-entity-tx+prefix-iterator i entity-value-entity-idx attr prefix-eb)
          entity-tx ^EntityTx (.entity-tx entity-tx+prefix-iterator)
          i (.prefix-iterator entity-tx+prefix-iterator)]
      (when-let [k (kv/next i)]
        (MapEntry/create (mem/copy-buffer (.value (c/decode-aecv-key->evc-from k)))
                         entity-tx)))))

(defn new-doc-attribute-entity-value-value-index [snapshot attr entity-value-entity-idx]
  (->DocAttributeEntityValueValueIndex (kv/new-iterator snapshot) (c/->id-buffer attr) entity-value-entity-idx
                                       (ExpandableDirectByteBuffer.)))

;; Range Constraints

(defrecord PredicateVirtualIndex [idx pred seek-k-fn]
  db/Index
  (seek-values [this k]
    (when-let [value+results (db/seek-values idx (seek-k-fn k))]
      (when (pred (first value+results))
        value+results)))

  (next-values [this]
    (when-let [value+results (db/next-values idx)]
      (when (pred (first value+results))
        value+results))))

(defn- value-comparsion-predicate
  ([compare-pred compare-v]
   (value-comparsion-predicate compare-pred compare-v Integer/MAX_VALUE))
  ([compare-pred ^DirectBuffer compare-v max-length]
   (if compare-v
     (fn [value]
       (and value (compare-pred (mem/compare-buffers value compare-v max-length))))
     (constantly true))))

(defn new-prefix-equal-virtual-index [idx ^DirectBuffer prefix-v]
  (let [seek-k-pred (value-comparsion-predicate (comp not neg?) prefix-v (.capacity prefix-v))
        pred (value-comparsion-predicate zero? prefix-v (.capacity prefix-v))]
    (->PredicateVirtualIndex idx pred (fn [k]
                                        (if (seek-k-pred k)
                                          k
                                          prefix-v)))))

(defn new-less-than-equal-virtual-index [idx ^DirectBuffer max-v]
  (let [pred (value-comparsion-predicate (comp not pos?) max-v)]
    (->PredicateVirtualIndex idx pred identity)))

(defn new-less-than-virtual-index [idx ^DirectBuffer max-v]
  (let [pred (value-comparsion-predicate neg? max-v)]
    (->PredicateVirtualIndex idx pred identity)))

(defn new-greater-than-equal-virtual-index [idx ^DirectBuffer min-v]
  (let [pred (value-comparsion-predicate (comp not neg?) min-v)]
    (->PredicateVirtualIndex idx pred (fn [k]
                                        (if (pred k)
                                          k
                                          min-v)))))

(defrecord GreaterThanVirtualIndex [idx]
  db/Index
  (seek-values [this k]
    (or (db/seek-values idx k)
        (db/next-values idx)))

  (next-values [this]
    (db/next-values idx)))

(defn new-greater-than-virtual-index [idx ^DirectBuffer min-v]
  (let [pred (value-comparsion-predicate pos? min-v)
        idx (->PredicateVirtualIndex idx pred (fn [k]
                                                (if (pred k)
                                                  k
                                                  min-v)))]
    (->GreaterThanVirtualIndex idx)))

(defn new-equals-virtual-index [idx ^DirectBuffer v]
  (let [pred (value-comparsion-predicate zero? v)]
    (->PredicateVirtualIndex idx pred (fn [k]
                                        (if (pred k)
                                          k
                                          v)))))

(defn wrap-with-range-constraints [idx range-constraints]
  (if range-constraints
    (range-constraints idx)
    idx))

;; Meta

(defn meta-kv [k v]
  [(c/encode-meta-key-to (.get seek-buffer-tl) (c/->id-buffer k))
   (mem/->off-heap (nippy/fast-freeze v))])

(defn store-meta [kv k v]
  (kv/store kv [(meta-kv k v)]))

(defn read-meta [kv k]
  (let [seek-k (c/encode-meta-key-to (.get seek-buffer-tl) (c/->id-buffer k))]
    (with-open [snapshot (kv/new-snapshot kv)]
      (some->> (kv/get-value snapshot seek-k)
               (DirectBufferInputStream.)
               (DataInputStream.)
               (nippy/thaw-from-in!)))))

;; Object Store

(defn <-nippy-buffer [buf]
  (nippy/thaw-from-in! (DataInputStream. (DirectBufferInputStream. buf))))

(defn ->nippy-buffer [v]
  (mem/->off-heap (nippy/fast-freeze v)))

(defn evicted-doc?
  [{:crux.db/keys [id evicted?] :as doc}]
  (boolean (or (= :crux.db/evicted id) evicted?)))

(defn keep-non-evicted-doc [doc]
  (when-not (evicted-doc? doc)
    doc))

(defn multiple-values? [v]
  (or (vector? v) (set? v)))

(defn vectorize-value [v]
  (cond-> v
    (not (multiple-values? v))
    (vector)))

(defn doc-predicate-stats [doc evicted?]
  (->> (for [[k v] doc]
         [k (cond-> (count (vectorize-value v)) evicted? -)])
       (into {})))

(defn doc-idx-keys [content-hash doc]
  (let [id (c/->id-buffer (:crux.db/id doc))
        content-hash (c/->id-buffer content-hash)]
    (->> (for [[k v] doc
               :let [k (c/->id-buffer k)]
               v (vectorize-value v)
               :let [v (c/->value-buffer v)]
               :when (pos? (.capacity v))]
           [(c/encode-avec-key-to nil k v id content-hash)
            (c/encode-aecv-key-to nil k id content-hash v)])
         (apply concat))))

(defn store-doc-idx-keys [kv idx-keys]
  (kv/store kv (for [k idx-keys]
                 [k c/empty-buffer])))

(defn delete-doc-idx-keys [kv idx-keys]
  (kv/delete kv idx-keys))

;; Utils

(defn current-index-version [kv]
  (with-open [snapshot (kv/new-snapshot kv)]
    (some->> (kv/get-value snapshot (c/encode-index-version-key-to (.get seek-buffer-tl)))
             (c/decode-index-version-value-from))))

(defn check-and-store-index-version [kv]
  (if-let [index-version (current-index-version kv)]
    (when (not= c/index-version index-version)
      (throw (IndexVersionOutOfSyncException.
              (str "Index version on disk: " index-version " does not match index version of code: " c/index-version))))
    (doto kv
      (kv/store [[(c/encode-index-version-key-to nil)
                  (c/encode-index-version-value-to nil c/index-version)]])
      (kv/fsync)))
  kv)

;; NOTE: We need to copy the keys and values here, as the originals
;; returned by the iterator will (may) get invalidated by the next
;; iterator call.

(defn all-keys-in-prefix
  ([i prefix] (all-keys-in-prefix i prefix prefix {}))
  ([i prefix seek-k] (all-keys-in-prefix i prefix seek-k {}))
  ([i ^DirectBuffer prefix, ^DirectBuffer seek-k, {:keys [entries? reverse?]}]
   (letfn [(step [k]
             (lazy-seq
              (when (and k (mem/buffers=? prefix k (.capacity prefix)))
                (cons (if entries?
                        (MapEntry/create (mem/copy-to-unpooled-buffer k) (mem/copy-to-unpooled-buffer (kv/value i)))
                        (mem/copy-to-unpooled-buffer k))
                      (step (if reverse? (kv/prev i) (kv/next i)))))))]
     (step (if reverse?
             (when (kv/seek i (-> seek-k (mem/copy-buffer) (mem/inc-unsigned-buffer!)))
               (kv/prev i))
             (kv/seek i seek-k))))))

(defn idx->series
  [idx]
  (reify
    IReduceInit
    (reduce [_ rf init]
      (loop [ret (rf init (db/seek-values idx nil))]
        (if-let [x (db/next-values idx)]
          (let [ret (rf ret x)]
            (if (reduced? ret)
              @ret
              (recur ret)))
          ret)))
    Seqable
    (seq [_]
      (when-let [result (db/seek-values idx nil)]
        (->> (repeatedly #(db/next-values idx))
             (take-while identity)
             (cons result))))
    Sequential))

(defn idx->seq
  [idx]
  (seq (idx->series idx)))

;; Entities

(defn- ^EntityTx enrich-entity-tx [entity-tx ^DirectBuffer content-hash]
  (assoc entity-tx :content-hash (when (pos? (.capacity content-hash))
                                   (c/safe-id (c/new-id content-hash)))))

(defn- safe-entity-tx ^crux.codec.EntityTx [entity-tx]
  (-> entity-tx
      (update :eid c/safe-id)
      (update :content-hash c/safe-id)))

(defn- find-first-entity-tx-within-range [i min max eid]
  (let [prefix-size (+ c/index-id-size c/id-size)
        seek-k (c/encode-entity+z+tx-id-key-to
                (.get seek-buffer-tl)
                eid
                min)]
    (loop [k (kv/seek i seek-k)]
      (when (and k (mem/buffers=? seek-k k prefix-size))
        (let [z (c/decode-entity+z+tx-id-key-as-z-number-from k)]
          (if (morton/morton-number-within-range? min max z)
            (let [entity-tx (safe-entity-tx (c/decode-entity+z+tx-id-key-from k))
                  v (kv/value i)]
              (if-not (mem/buffers=? c/nil-id-buffer v)
                [(c/->id-buffer (.eid entity-tx))
                 (enrich-entity-tx entity-tx v)
                 z]
                [::deleted-entity entity-tx z]))
            (let [[litmax bigmin] (morton/morton-range-search min max z)]
              (when-not (neg? (.compareTo ^Comparable bigmin z))
                (recur (kv/seek i (c/encode-entity+z+tx-id-key-to
                                   (.get seek-buffer-tl)
                                   eid
                                   bigmin)))))))))))

(defn- find-entity-tx-within-range-with-highest-valid-time [i min max eid prev-candidate]
  (if-let [[_ ^EntityTx entity-tx z :as candidate] (find-first-entity-tx-within-range i min max eid)]
    (let [[^long x ^long y] (morton/morton-number->longs z)
          min-x (long (first (morton/morton-number->longs min)))
          max-x (dec x)]
      (if (and (not (pos? (Long/compareUnsigned min-x max-x)))
               (not= y -1))
        (let [min (morton/longs->morton-number
                   min-x
                   (unchecked-inc y))
              max (morton/longs->morton-number
                   max-x
                   -1)]
          (recur i min max eid candidate))
        candidate))
    prev-candidate))

(def ^:private min-date (Date. Long/MIN_VALUE))
(def ^:private max-date (Date. Long/MAX_VALUE))

(defn entity-as-of [i eid valid-time transact-time]
  (let [prefix-size (+ c/index-id-size c/id-size)
        eid-buffer (c/->id-buffer eid)
        seek-k (c/encode-entity+vt+tt+tx-id-key-to
                (.get seek-buffer-tl)
                eid-buffer
                valid-time
                transact-time
                nil)]
    (loop [k (kv/seek i seek-k)]
      (when (and k (mem/buffers=? seek-k k prefix-size))
        (let [entity-tx (safe-entity-tx (c/decode-entity+vt+tt+tx-id-key-from k))
              v (kv/value i)]
          (if (<= (compare (.tt entity-tx) transact-time) 0)
            (when-not (mem/buffers=? c/nil-id-buffer v)
              (enrich-entity-tx entity-tx v))
            (if morton/*use-space-filling-curve-index?*
              (let [seek-z (c/encode-entity-tx-z-number valid-time transact-time)]
                (when-let [[k v] (find-entity-tx-within-range-with-highest-valid-time i seek-z morton/z-max-mask eid-buffer nil)]
                  (when-not (= ::deleted-entity k)
                    v)))
              (recur (kv/next i)))))))))

;; TODO: Only used by tests.
(defn entities-at [snapshot eids valid-time transact-time]
  (with-open [i (kv/new-iterator snapshot)]
    (some->> (for [eid eids
                   :let [entity-tx (entity-as-of i eid valid-time transact-time)]
                   :when entity-tx]
               entity-tx)
             (not-empty)
             (vec))))

(defn- entity-history-range-step [i min max ^EntityHistoryRangeState state k]
  (loop [k k]
    (when (and k (mem/buffers=? (.prefix state) k (.capacity ^DirectBuffer (.prefix state))))
      (let [z (c/decode-entity+z+tx-id-key-as-z-number-from k)]
        (when (not (neg? (.compareTo ^Comparable max z)))
          (if (morton/morton-number-within-range? min max z)
            (let [entity-tx (safe-entity-tx (c/decode-entity+z+tx-id-key-from k))
                  v (kv/value i)]
              (MapEntry/create (c/->id-buffer (.eid entity-tx))
                               (enrich-entity-tx entity-tx v)))
            (let [[litmax bigmin] (morton/morton-range-search min max z)]
              (when-not (neg? (.compareTo ^Comparable bigmin z))
                (recur (kv/seek i (c/encode-entity+z+tx-id-key-to
                                   (.get seek-buffer-tl)
                                   (.eid state)
                                   bigmin)))))))))))

(defrecord EntityHistoryRangeIndex [i min max ^EntityHistoryRangeState state]
  db/Index
  (db/seek-values [this k]
    (let [prefix-size (+ c/index-id-size c/id-size)
          seek-k (c/encode-entity+z+tx-id-key-to
                  (.get seek-buffer-tl)
                  k
                  min)]
      (set! (.eid state) k)
      (set! (.prefix state) (mem/copy-buffer seek-k prefix-size))
      (entity-history-range-step i min max state (kv/seek i seek-k))))

  (db/next-values [this]
    (entity-history-range-step i min max state (kv/next i))))

(defn new-entity-history-range-index [snapshot vt-start tt-start vt-end tt-end]
  (let [vt-start (or vt-start min-date)
        tt-start (or tt-start min-date)
        vt-end (or vt-end max-date)
        tt-end (or tt-end max-date)
        [min max] (sort (reify Comparator
                          (compare [_ a b]
                            (.compareTo ^Comparable a b)))
                        [(c/encode-entity-tx-z-number vt-start tt-start)
                         (c/encode-entity-tx-z-number vt-end tt-end)])]
    (->EntityHistoryRangeIndex (kv/new-iterator snapshot) min max
                               (EntityHistoryRangeState. nil nil))))

(defn entity-history-range [snapshot eid vt-start tt-start vt-end tt-end]
  (let [idx (new-entity-history-range-index snapshot vt-start tt-start vt-end tt-end)]
    (when-let [result (db/seek-values idx (c/->id-buffer eid))]
      (->> (repeatedly #(db/next-values idx))
           (take-while identity)
           (cons result)
           (not-empty)
           (map second)))))

(defn- ->entity-tx [[k v]]
  (-> (c/decode-entity+vt+tt+tx-id-key-from k)
      (enrich-entity-tx v)))

(defn entity-history-seq-ascending
  ([i eid] ([i eid] (entity-history-seq-ascending i eid {})))
  ([i eid {{^Date start-vt :crux.db/valid-time, ^Date start-tt :crux.tx/tx-time} :start
           {^Date end-vt :crux.db/valid-time, ^Date end-tt :crux.tx/tx-time} :end
           :keys [with-corrections?]}]
   (let [seek-k (c/encode-entity+vt+tt+tx-id-key-to nil (c/->id-buffer eid) start-vt)]
     (-> (all-keys-in-prefix i (mem/limit-buffer seek-k (+ c/index-id-size c/id-size)) seek-k
                             {:reverse? true, :entries? true})
         (->> (map ->entity-tx))
         (cond->> end-vt (take-while (fn [^EntityTx entity-tx]
                                       (neg? (compare (.vt entity-tx) end-vt))))
                  start-tt (remove (fn [^EntityTx entity-tx]
                                     (neg? (compare (.tt entity-tx) start-tt))))
                  end-tt (filter (fn [^EntityTx entity-tx]
                                   (neg? (compare (.tt entity-tx) end-tt)))))
         (cond-> (not with-corrections?) (->> (partition-by :vt)
                                              (map last)))))))

(defn entity-history-seq-descending
  ([i eid] (entity-history-seq-descending i eid {}))
  ([i eid {{^Date start-vt :crux.db/valid-time, ^Date start-tt :crux.tx/tx-time} :start
           {^Date end-vt :crux.db/valid-time, ^Date end-tt :crux.tx/tx-time} :end
           :keys [with-corrections?]}]
   (let [seek-k (c/encode-entity+vt+tt+tx-id-key-to nil (c/->id-buffer eid) start-vt)]
     (-> (all-keys-in-prefix i (-> seek-k (mem/limit-buffer (+ c/index-id-size c/id-size))) seek-k
                             {:entries? true})
         (->> (map ->entity-tx))
         (cond->> end-vt (take-while (fn [^EntityTx entity-tx]
                                         (pos? (compare (.vt entity-tx) end-vt))))
                  start-tt (remove (fn [^EntityTx entity-tx]
                                    (pos? (compare (.tt entity-tx) start-tt))))
                  end-tt (filter (fn [^EntityTx entity-tx]
                                   (pos? (compare (.tt entity-tx) end-tt)))))
         (cond-> (not with-corrections?) (->> (partition-by :vt)
                                              (map first)))))))

(defn all-content-hashes [snapshot eid]
  (with-open [i (kv/new-iterator snapshot)]
    (->> (all-keys-in-prefix i (c/encode-aecv-key-to (.get seek-buffer-tl) (c/->id-buffer :crux.db/id) (c/->id-buffer eid)))
         (map c/decode-aecv-key->evc-from)
         (map #(.content-hash ^EntityValueContentHash %))
         (set))))

;; Join

(extend-protocol db/LayeredIndex
  Object
  (open-level [_])
  (close-level [_])
  (max-depth [_] 1))

(def ^:private sorted-virtual-index-key-comparator
  (reify Comparator
    (compare [_ [a] [b]]
      (mem/compare-buffers (or a c/empty-buffer)
                           (or b c/empty-buffer)))))

(defrecord SortedVirtualIndex [values ^SortedVirtualIndexState state]
  db/Index
  (seek-values [this k]
    (let [idx (Collections/binarySearch values
                                        [k]
                                        sorted-virtual-index-key-comparator)
          [x & xs] (subvec values (if (neg? idx)
                                    (dec (- idx))
                                    idx))]
      (set! (.seq state) (seq xs))
      x))

  (next-values [this]
    (when-let [[x & xs] (.seq state)]
      (set! (.seq state) (seq xs))
      x)))

(defn new-sorted-virtual-index [idx-or-seq]
  (let [idx-as-seq (if (satisfies? db/Index idx-or-seq)
                     (idx->seq idx-or-seq)
                     idx-or-seq)]
    (->SortedVirtualIndex
     (->> idx-as-seq
          (sort-by first mem/buffer-comparator)
          (distinct)
          (vec))
     (SortedVirtualIndexState. nil))))

;; NOTE: Not used by production code, kept for reference.
(defrecord OrVirtualIndex [indexes ^objects peek-state]
  db/Index
  (seek-values [this k]
    (loop [[idx & indexes] indexes
           i 0]
      (when idx
        (aset peek-state i (db/seek-values idx k))
        (recur indexes (inc i))))
    (db/next-values this))

  (next-values [this]
    (let [[n value] (->> (map-indexed vector peek-state)
                         (remove (comp nil? second))
                         (sort-by (comp first second) mem/buffer-comparator)
                         (first))]
      (when n
        (aset peek-state n (db/next-values (get indexes n))))
      value)))

(defn new-or-virtual-index [indexes]
  (->OrVirtualIndex indexes (object-array (count indexes))))

(defn- new-unary-join-iterator-state [idx [value results]]
  (let [result-name (:name idx)]
    (UnaryJoinIteratorState.
     idx
     (or value c/empty-buffer)
     (when (and result-name results)
       {result-name results}))))

(defrecord UnaryJoinVirtualIndex [indexes ^UnaryJoinIteratorsThunkFnState state]
  db/Index
  (seek-values [this k]
    (->> #(let [iterators (->> (for [idx indexes]
                                 (new-unary-join-iterator-state idx (db/seek-values idx k)))
                               (sort-by (fn [x] (.key ^UnaryJoinIteratorState x)) mem/buffer-comparator)
                               (vec))]
            (UnaryJoinIteratorsThunkState. iterators 0))
         (set! (.thunk state)))
    (db/next-values this))

  (next-values [this]
    (when-let [iterators-thunk (.thunk state)]
      (when-let [iterators-thunk ^UnaryJoinIteratorsThunkState (iterators-thunk)]
        (let [iterators (.iterators iterators-thunk)
              index (.index iterators-thunk)
              iterator-state ^UnaryJoinIteratorState (nth iterators index nil)
              max-index (mod (dec index) (count iterators))
              max-k (.key ^UnaryJoinIteratorState (nth iterators max-index nil))
              match? (mem/buffers=? (.key iterator-state) max-k)
              idx (.idx iterator-state)]
          (->> #(let [next-value+results (if match?
                                           (db/next-values idx)
                                           (db/seek-values idx max-k))]
                  (when next-value+results
                    (set! (.iterators iterators-thunk)
                          (assoc iterators index (new-unary-join-iterator-state idx next-value+results)))
                    (set! (.index iterators-thunk) (mod (inc index) (count iterators)))
                    iterators-thunk))
               (set! (.thunk state)))
          (if match?
            (let [new-results (map (fn [x] (.results ^UnaryJoinIteratorState x)) iterators)]
              (when-let [result (->> new-results
                                     (apply merge-with
                                            (fn [x y]
                                              (if (or (= :crux.index.binary-placeholder/entity y)
                                                      (= :crux.index.binary-placeholder/value y))
                                                x y))))]
                (MapEntry/create max-k result)))
            (recur))))))

  db/LayeredIndex
  (open-level [this]
    (doseq [idx indexes]
      (db/open-level idx)))

  (close-level [this]
    (doseq [idx indexes]
      (db/close-level idx)))

  (max-depth [this]
    1))

(defn new-unary-join-virtual-index [indexes]
  (->UnaryJoinVirtualIndex indexes (UnaryJoinIteratorsThunkFnState. nil)))

(defn constrain-join-result-by-empty-names [join-keys join-results]
  (when (not-any? nil? (vals join-results))
    join-results))

(defrecord NAryJoinLayeredVirtualIndex [unary-join-indexes ^NAryJoinLayeredVirtualIndexState state]
  db/Index
  (seek-values [this k]
    (db/seek-values (nth unary-join-indexes (.depth state) nil) k))

  (next-values [this]
    (db/next-values (nth unary-join-indexes (.depth state) nil)))

  db/LayeredIndex
  (open-level [this]
    (db/open-level (nth unary-join-indexes (.depth state) nil))
    (set! (.depth state) (inc (.depth state)))
    nil)

  (close-level [this]
    (db/close-level (nth unary-join-indexes (dec (long (.depth state))) nil))
    (set! (.depth state) (dec (.depth state)))
    nil)

  (max-depth [this]
    (count unary-join-indexes)))

(defn new-n-ary-join-layered-virtual-index [indexes]
  (->NAryJoinLayeredVirtualIndex indexes (NAryJoinLayeredVirtualIndexState. 0)))

(defrecord BinaryJoinLayeredVirtualIndex [^BinaryJoinLayeredVirtualIndexState state]
  db/Index
  (seek-values [this k]
    (db/seek-values (nth (.indexes state) (.depth state) nil) k))

  (next-values [this]
    (db/next-values (nth (.indexes state) (.depth state) nil)))

  db/LayeredIndex
  (open-level [this]
    (db/open-level (nth (.indexes state) (.depth state) nil))
    (set! (.depth state) (inc (.depth state)))
    nil)

  (close-level [this]
    (db/close-level (nth (.indexes state) (dec (long (.depth state))) nil))
    (set! (.depth state) (dec (.depth state)))
    nil)

  (max-depth [this]
    2))

(defn new-binary-join-virtual-index
  ([]
   (new-binary-join-virtual-index nil nil))
  ([lhs-index rhs-index]
   (->BinaryJoinLayeredVirtualIndex (BinaryJoinLayeredVirtualIndexState.
                                     [lhs-index rhs-index]
                                     0))))

(defn update-binary-join-order! [^BinaryJoinLayeredVirtualIndex binary-join-index lhs-index rhs-index]
  (set! (.indexes ^BinaryJoinLayeredVirtualIndexState (.state binary-join-index)) [lhs-index rhs-index])
  binary-join-index)

(defn- build-constrained-result [constrain-result-fn result-stack [max-k new-values]]
  (let [[max-ks parent-result] (last result-stack)
        join-keys (conj (or max-ks []) max-k)]
    (when-let [join-results (->> (merge parent-result new-values)
                                 (constrain-result-fn join-keys)
                                 (not-empty))]
      (conj result-stack [join-keys join-results]))))

(defrecord NAryConstrainingLayeredVirtualIndex [n-ary-index constrain-result-fn ^NAryWalkState state]
  db/Index
  (seek-values [this k]
    (when-let [[value :as values] (db/seek-values n-ary-index k)]
      (if-let [result (build-constrained-result constrain-result-fn (.result-stack state) values)]
        (do (set! (.last state) result)
            (MapEntry/create value (second (last result))))
        (db/next-values this))))

  (next-values [this]
    (when-let [[value :as values] (db/next-values n-ary-index)]
      (if-let [result (build-constrained-result constrain-result-fn (.result-stack state) values)]
        (do (set! (.last state) result)
            (MapEntry/create value (second (last result))))
        (recur))))

  db/LayeredIndex
  (open-level [this]
    (db/open-level n-ary-index)
    (set! (.result-stack state) (.last state))
    nil)

  (close-level [this]
    (db/close-level n-ary-index)
    (set! (.result-stack state) (pop (.result-stack state)))
    nil)

  (max-depth [this]
    (db/max-depth n-ary-index)))

(defn new-n-ary-constraining-layered-virtual-index [idx constrain-result-fn]
  (->NAryConstrainingLayeredVirtualIndex idx constrain-result-fn (NAryWalkState. [] nil)))

(defn layered-idx->seq [idx]
  (when idx
    (let [max-depth (long (db/max-depth idx))
          build-result (fn [max-ks [max-k new-values]]
                         (when new-values
                           (conj max-ks max-k)))
          build-leaf-results (fn [max-ks idx]
                               (for [result (idx->seq idx)
                                     :let [leaf-key (build-result max-ks result)]
                                     :when leaf-key]
                                 (MapEntry/create leaf-key (last result))))
          step (fn step [max-ks ^long depth needs-seek?]
                 (when (Thread/interrupted)
                   (throw (InterruptedException.)))
                 (let [close-level (fn []
                                     (when (pos? depth)
                                       (lazy-seq
                                        (db/close-level idx)
                                        (step (pop max-ks) (dec depth) false))))
                       open-level (fn [result]
                                    (db/open-level idx)
                                    (if-let [max-ks (build-result max-ks result)]
                                      (step max-ks (inc depth) true)
                                      (do (db/close-level idx)
                                          (step max-ks depth false))))]
                   (if (= depth (dec max-depth))
                     (concat (build-leaf-results max-ks idx)
                             (close-level))
                     (if-let [result (if needs-seek?
                                       (db/seek-values idx nil)
                                       (db/next-values idx))]
                       (open-level result)
                       (close-level)))))]
      (when (pos? max-depth)
        (step [] 0 true)))))

(defn- relation-virtual-index-depth ^long [^RelationIteratorsState iterators-state]
  (dec (count (.indexes iterators-state))))

(defrecord RelationVirtualIndex [relation-name max-depth layered-range-constraints encode-value-fn ^RelationIteratorsState state]
  db/Index
  (seek-values [this k]
    (when-let [idx (last (.indexes state))]
      (let [[k ^RelationNestedIndexState nested-index-state] (db/seek-values idx k)]
        (set! (.child-idx state) (some-> nested-index-state (.child-idx)))
        (set! (.needs-seek? state) false)
        (when k
          (MapEntry/create k (.value nested-index-state))))))

  (next-values [this]
    (if (.needs-seek? state)
      (db/seek-values this nil)
      (when-let [idx (last (.indexes state))]
        (let [[k ^RelationNestedIndexState nested-index-state] (db/next-values idx)]
          (set! (.child-idx state) (some-> nested-index-state (.child-idx)))
          (when k
            (MapEntry/create k (.value nested-index-state)))))))

  db/LayeredIndex
  (open-level [this]
    (when (= max-depth (relation-virtual-index-depth state))
      (throw (IllegalStateException. (str "Cannot open level at max depth: " max-depth))))
    (set! (.indexes state) (conj (.indexes state) (.child-idx state)))
    (set! (.child-idx state) nil)
    (set! (.needs-seek? state) true)
    nil)

  (close-level [this]
    (when (zero? (relation-virtual-index-depth state))
      (throw (IllegalStateException. "Cannot close level at root.")))
    (set! (.indexes state) (pop (.indexes state)))
    (set! (.child-idx state) nil)
    (set! (.needs-seek? state) false)
    nil)

  (max-depth [this]
    max-depth))

(defn- build-nested-index [encode-value-fn tuples [range-constraints & next-range-constraints]]
  (-> (new-sorted-virtual-index
       (for [prefix (vals (group-by first tuples))
             :let [value (ffirst prefix)]]
         (MapEntry/create (encode-value-fn value)
                          (RelationNestedIndexState.
                           value
                           (when (seq (next (first prefix)))
                             (build-nested-index encode-value-fn (map next prefix) next-range-constraints))))))
      (wrap-with-range-constraints range-constraints)))

(defn update-relation-virtual-index!
  ([^RelationVirtualIndex relation tuples]
   (update-relation-virtual-index! relation tuples (.layered-range-constraints relation)))
  ([^RelationVirtualIndex relation tuples layered-range-constraints]
   (let [state ^RelationIteratorsState (.state relation)]
     (set! (.indexes state) [(binding [nippy/*freeze-fallback* :write-unfreezable]
                               (build-nested-index (.encode-value-fn relation) tuples layered-range-constraints))])
     (set! (.child-idx state) nil)
     (set! (.needs-seek? state) false))
   relation))

(defn new-relation-virtual-index
  ([relation-name tuples max-depth encode-value-fn]
   (new-relation-virtual-index relation-name tuples max-depth encode-value-fn nil))
  ([relation-name tuples max-depth encode-value-fn layered-range-constraints]
   (let [iterators-state (RelationIteratorsState. nil nil false)]
     (update-relation-virtual-index! (->RelationVirtualIndex relation-name max-depth layered-range-constraints encode-value-fn iterators-state) tuples))))
