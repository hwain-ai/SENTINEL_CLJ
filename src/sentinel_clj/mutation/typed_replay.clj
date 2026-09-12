(ns sentinel-clj.mutation.typed-replay)

(def report-keys
  #{:schemaVersion :nonce :tests :failedTests :errorTests
    :failureSignatures :errorSignatures :summary})
(def summary-keys #{:test :fail :error})

(defn- signature-vector? [values]
  (and (vector? values)
       (every? #(and (string? %) (boolean (re-matches #"[0-9a-f]{64}" %))) values)
       (= values (vec (sort values)))))

(defn- nonempty-signatures? [values]
  (and (seq values) (signature-vector? values)))

(defn- sorted-unique-strings? [values]
  (and (vector? values)
       (every? string? values)
       (= values (vec (sort values)))
       (= (count values) (count (set values)))))

(defn- valid-header? [report nonce]
  (and (map? report)
       (= report-keys (set (keys report)))
       (= "sentinel-clojure-test-events-v2" (:schemaVersion report))
       (= nonce (:nonce report))
       (= summary-keys (set (keys (:summary report))))))

(defn- valid-inventories? [report]
  (and (sorted-unique-strings? (:tests report))
       (seq (:tests report))
       (sorted-unique-strings? (:failedTests report))
       (sorted-unique-strings? (:errorTests report))
       (signature-vector? (:failureSignatures report))
       (signature-vector? (:errorSignatures report))))

(defn- valid-counts? [report]
  (let [summary (:summary report)]
    (and (every? #(and (integer? %) (not (neg? %))) (vals summary))
         (= (:test summary) (count (:tests report)))
         (= (:fail summary) (count (:failureSignatures report)))
         (= (:error summary) (count (:errorSignatures report))))))

(defn- valid-links? [report]
  (let [test-ids (set (:tests report))
        fail-count (get-in report [:summary :fail])
        error-count (get-in report [:summary :error])]
    (and (every? test-ids (:failedTests report))
         (every? test-ids (:errorTests report))
         (= (pos? fail-count) (boolean (seq (:failedTests report))))
         (= (pos? error-count) (boolean (seq (:errorTests report)))))))

(defn valid-event-report? [report nonce]
  (and (valid-header? report nonce)
       (valid-inventories? report)
       (valid-counts? report)
       (valid-links? report)))

(defn matching-selected-control? [selected control]
  (boolean
   (and (seq selected)
        (sorted-unique-strings? selected)
        (= "passed" (:raw-status control))
        (= selected (:tests control)))))

(defn matching-assertion-replay? [first-run replay]
  (let [selected (:failed-test-ids first-run)
        first-signatures (:failure-signatures first-run)
        replay-signatures (:failure-signatures replay)]
    (boolean
     (and (= "assertionFailure" (:raw-status first-run))
          (= "assertionFailure" (:raw-status replay))
          (seq selected)
          (sorted-unique-strings? selected)
          (= selected (:tests replay))
          (nonempty-signatures? first-signatures)
          (nonempty-signatures? replay-signatures)
          (= first-signatures replay-signatures)))))
