(ns sentinel-clj.mutation.gate-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sentinel-clj.mutation.gate :as gate]))

(defn- golden []
  (json/read-str (slurp (io/resource "golden/gate/mutation-v1.json")) :key-fn keyword))

(defn- error-code [thunk]
  (:error (ex-data (try (thunk) (catch Exception error error)))))

(deftest matches-every-shared-mutation-gate-vector
  (doseq [{case-id :id counts :counts in-scope :inScope
           unauthorized :unauthorizedExclusion expected :expected} (:cases (golden))]
    (testing case-id
      (let [result (gate/evaluate-counts counts in-scope unauthorized)]
        (is (= (:pass expected) (:pass? result)))
        (is (= (:killRate expected)
               (when-let [rate (:kill-rate result)]
                 {:numerator (str (:numerator rate))
                  :denominator (str (:denominator rate))})))))))

(deftest rejects-every-shared-invalid-mutation-gate-vector
  (doseq [{case-id :id counts :counts in-scope :inScope
           unauthorized :unauthorizedExclusion expected :error} (:invalidCases (golden))]
    (testing case-id
      (is (= (keyword expected)
             (error-code #(gate/evaluate-counts counts in-scope unauthorized)))))))

(deftest candidate-and-result-inventories-must-match-exactly
  (is (= :duplicateCandidateId
         (error-code #(gate/evaluate-records ["m1" "m1"]
                                             [{:candidate-id "m1" :status :killed}]
                                             0))))
  (is (= :duplicateMutationResult
         (error-code #(gate/evaluate-records ["m1"]
                                             [{:candidate-id "m1" :status :killed}
                                              {:candidate-id "m1" :status :killed}]
                                             0))))
  (is (= :candidateResultSetMismatch
         (error-code #(gate/evaluate-records ["m1"]
                                             [{:candidate-id "m2" :status :killed}]
                                             0))))
  (is (= :unknownMutationState
         (error-code #(gate/evaluate-records ["m1"]
                                             [{:candidate-id "m1" :status :unknown}]
                                             0)))))
