(ns sentinel-clj.mutation.progress-test
  (:require [clojure.test :refer [deftest is testing]]
            [sentinel-clj.json :as contract-json]
            [sentinel-clj.mutation.progress :as progress])
  (:import [java.nio.file Files LinkOption OpenOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(deftest watchdog-distinguishes-startup-idle-and-absolute-timeouts
  (let [limits {:startup-timeout-ms 100
                :idle-timeout-ms 50
                :absolute-timeout-ms 500}]
    (is (= :running
           (progress/watchdog-status limits 0 99 nil)))
    (is (= :startup-timeout
           (progress/watchdog-status limits 0 100 nil)))
    (is (= :running
           (progress/watchdog-status limits 0 149 100)))
    (is (= :idle-timeout
           (progress/watchdog-status limits 0 150 100)))
    (is (= :running
           (progress/watchdog-status limits 0 450 430)))
    (is (= :absolute-timeout
           (progress/watchdog-status limits 0 500 499)))))

(deftest signed-progress-rejects-tamper-and-never-stores-the-key
  (let [root (Files/createTempDirectory "sentinel-clj-progress-test-"
                                        (make-array FileAttribute 0))
        contract (progress/new-contract root)]
    (try
      (progress/write! contract 1 "started" 0 0)
      (let [record (progress/read! contract)
            path (:path contract)]
        (is (= 1 (:sequence record)))
        (is (= "started" (:phase record)))
        (is (not-any? #{(:key contract)} (vals record)))
        (Files/write path
                     (contract-json/write-canonical-bytes (assoc record :sequence 2))
                     (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                             StandardOpenOption/WRITE]))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authentication"
                              (progress/read! contract))))
      (finally
        (when (Files/exists root (make-array LinkOption 0))
          (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
            (doseq [path (reverse (sort-by #(.getNameCount %) (iterator-seq (.iterator paths))))]
              (Files/delete path))))))))

(deftest progress-contract-rejects-invalid-state-shapes
  (let [root (Files/createTempDirectory "sentinel-clj-progress-shape-"
                                        (make-array FileAttribute 0))
        contract (progress/new-contract root)]
    (try
      (doseq [[label arguments]
              [["zero sequence" [0 "started" 0 0]]
               ["unknown phase" [1 "unknown" 0 0]]
               ["completed beyond total" [1 "candidate" 2 1]]]]
        (testing label
          (is (thrown? clojure.lang.ExceptionInfo
                       (apply progress/write! contract arguments)))))
      (finally
        (Files/deleteIfExists (:path contract))
        (Files/deleteIfExists root)))))
