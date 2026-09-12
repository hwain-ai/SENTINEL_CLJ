(ns sentinel-clj.mutation.clj-mutate-bridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [sentinel-clj.mutation.clj-mutate-bridge :as bridge]))

(def backend-commit "e27dd5df63c4efdd66438587d1c5f49e73661b69")
(def source-digest (apply str (repeat 64 "a")))
(def expected-source-inventory
  [{:moduleRelativePath "src/app/core.clj" :sourceDigest source-digest}])

(defn- report [raw-status]
  {:schemaVersion "sentinel-clj-mutate-report-v1"
   :backend {:name "clj-mutate" :sourceCommit backend-commit}
   :operatorInventory ["arithmetic" "boolean" "comparison" "conditional" "constant" "equality"]
   :sourceInventory [{:moduleRelativePath "src/app/core.clj"
                      :sourceDigest source-digest
                      :candidateIds ["m1"]}]
   :candidates [{:id "m1" :operator "conditional"}]
   :outcomes [(cond-> {:id "m1" :rawStatus raw-status}
                (= raw-status "assertionFailure")
                (assoc :controlPassed true :replayMatched true))]
   :unauthorizedExclusion 0})

(defn- error-code [thunk]
  (:error (ex-data (try (thunk) (catch Exception error error)))))

(deftest maps-all-nine-raw-outcomes-without-promoting-timeout
  (doseq [[raw expected] [["assertionFailure" :killed]
                          ["passed" :survived]
                          ["uncovered" :uncovered]
                          ["timeout" :timedOut]
                          ["compileError" :compileError]
                          ["runtimeError" :runtimeError]
                          ["pending" :pending]
                          ["ignored" :ignored]
                          ["toolError" :toolError]]]
    (testing raw
      (is (= expected (-> (bridge/normalize-report (report raw) expected-source-inventory)
                          :records first :status))))))

(deftest killed-needs-control-and-deterministic-assertion-replay
  (doseq [changed [{:controlPassed false}
                   {:replayMatched false}
                   {:controlPassed nil}
                   {:replayMatched nil}]]
    (let [input (update-in (report "assertionFailure") [:outcomes 0] merge changed)]
      (is (= :killProofInvalid
             (error-code #(bridge/normalize-report input expected-source-inventory)))))))

(deftest bridge-rejects-wrong-backend-unknown-status-and-incomplete-inventory
  (is (= :backendIdentityMismatch
         (error-code #(bridge/normalize-report
                       (assoc-in (report "passed") [:backend :sourceCommit] "wrong")
                       expected-source-inventory))))
  (is (= :unknownRawState
         (error-code #(bridge/normalize-report (report "magic") expected-source-inventory))))
  (is (= :candidateResultSetMismatch
         (error-code #(bridge/normalize-report
                       (assoc (report "passed") :outcomes [])
                       expected-source-inventory)))))

(deftest source-inventory-joins-every-production-file-to-the-candidate-plan
  (let [empty-source {:moduleRelativePath "src/app/empty.clj"
                      :sourceDigest (apply str (repeat 64 "b"))}
        expected (conj expected-source-inventory empty-source)
        complete (update (report "assertionFailure") :sourceInventory conj
                         (assoc empty-source :candidateIds []))]
    (is (= expected (:source-inventory (bridge/normalize-report complete expected))))
    (is (= :sourceInventoryMismatch
           (error-code #(bridge/normalize-report (report "assertionFailure") expected))))
    (is (= :candidateSourceJoinMismatch
           (error-code #(bridge/normalize-report
                         (assoc-in complete [:sourceInventory 1 :candidateIds] ["m1"])
                         expected))))))
