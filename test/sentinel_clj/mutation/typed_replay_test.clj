(ns sentinel-clj.mutation.typed-replay-test
  (:require [clojure.test :refer [deftest is]]
            [sentinel-clj.mutation.typed-replay :as replay]))

(def signature-a (apply str (repeat 64 "a")))
(def signature-b (apply str (repeat 64 "b")))

(deftest replay-requires-the-same-typed-failure-signatures
  (let [first-run {:raw-status "assertionFailure"
                   :failed-test-ids ["sample.namespace/same-test"]
                   :failure-signatures [signature-a]}
        exact-replay {:raw-status "assertionFailure"
                      :tests ["sample.namespace/same-test"]
                      :failure-signatures [signature-a]}
        different-assertion {:raw-status "assertionFailure"
                             :tests ["sample.namespace/same-test"]
                             :failure-signatures [signature-b]}
        different-tests {:raw-status "assertionFailure"
                         :tests ["sample.namespace/other-test"]
                         :failure-signatures [signature-a]}
        empty-signatures {:raw-status "assertionFailure"
                          :failed-test-ids ["sample.namespace/same-test"]
                          :tests ["sample.namespace/same-test"]
                          :failure-signatures []}
        runtime-error {:raw-status "runtimeError"
                       :tests ["sample.namespace/same-test"]
                       :failure-signatures [signature-a]}]
    (is (true? (replay/matching-assertion-replay? first-run exact-replay)))
    (is (false? (replay/matching-assertion-replay? first-run different-assertion)))
    (is (false? (replay/matching-assertion-replay? first-run different-tests)))
    (is (false? (replay/matching-assertion-replay? empty-signatures empty-signatures)))
    (is (false? (replay/matching-assertion-replay? first-run runtime-error)))))

(deftest selected-control-must-pass-exactly-the-failed-test-vars
  (let [selected ["sample.namespace/same-test"]]
    (is (true? (replay/matching-selected-control?
                selected {:raw-status "passed" :tests selected})))
    (is (false? (replay/matching-selected-control?
                 selected {:raw-status "passed" :tests ["sample.namespace/other-test"]})))
    (is (false? (replay/matching-selected-control?
                 selected {:raw-status "runtimeError" :tests selected})))
    (is (false? (replay/matching-selected-control? [] {:raw-status "passed" :tests []})))))

(def valid-event-report
  {:schemaVersion "sentinel-clojure-test-events-v2"
   :nonce "fresh-nonce"
   :tests ["sample.namespace/same-test"]
   :failedTests ["sample.namespace/same-test"]
   :errorTests []
   :failureSignatures [signature-a signature-b]
   :errorSignatures []
   :summary {:test 1 :fail 2 :error 0}})

(deftest event-report-joins-every-typed-signature-to-its-summary
  (is (true? (replay/valid-event-report? valid-event-report "fresh-nonce")))
  (doseq [invalid [(assoc valid-event-report :failureSignatures [signature-a])
                   (assoc valid-event-report :failureSignatures ["not-a-signature"
                                                                  signature-b])
                   (assoc valid-event-report :failedTests ["missing.namespace/test"])
                   (assoc-in valid-event-report [:summary :fail] 0)
                   (assoc valid-event-report :nonce "stale-nonce")]]
    (is (false? (replay/valid-event-report? invalid "fresh-nonce")))))
