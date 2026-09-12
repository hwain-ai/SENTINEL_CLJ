(ns sentinel-clj.runner.clojure-test-events-test
  (:require [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [sentinel-clj.json :as contract-json]
            [sentinel-clj.runner.clojure-test-events])
  (:import [java.nio.file Files LinkOption Path]
           [java.util.concurrent TimeUnit]))

(def signature-key-a (apply str (repeat 64 "a")))
(def signature-key-b (apply str (repeat 64 "b")))
(def same-test-id "sample.namespace/same-test")

(defn- capture-event [assertion]
  (let [captured (atom nil)]
    (binding [test/report #(reset! captured %)]
      (assertion))
    (assoc @captured :file "same_test.clj" :line 17 :message nil)))

(defn- signature [key event]
  (when-let [signature-function
             (ns-resolve 'sentinel-clj.runner.clojure-test-events
                         'failure-signature)]
    (signature-function key same-test-id [] event)))

(defn- write-file! [^Path path content]
  (Files/createDirectories (.getParent path)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (Files/writeString path content (make-array java.nio.file.OpenOption 0)))

(defn- delete-tree! [^Path root]
  (when (Files/exists root (make-array LinkOption 0))
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (reverse (sort-by #(.getNameCount ^Path %)
                                    (iterator-seq (.iterator paths))))]
        (Files/delete path)))))

(deftest assertion-signature-distinguishes-failures-within-one-test
  (let [first-event (capture-event #(is (= "expected-one" "actual-one")))
        second-event (capture-event #(is (= "expected-two" "actual-two")))
        first-signature (signature signature-key-a first-event)
        second-signature (signature signature-key-a second-event)]
    (is (= :fail (:type first-event)))
    (is (= :fail (:type second-event)))
    (is (not= first-signature second-signature))))

(deftest error-signature-distinguishes-throwable-class-within-one-test
  (let [first-event (capture-event
                     #(is (throw (IllegalArgumentException. "same message"))))
        second-event (capture-event
                      #(is (throw (IllegalStateException. "same message"))))
        first-event (assoc first-event :expected nil)
        second-event (assoc second-event :expected nil)]
    (is (= :error (:type first-event)))
    (is (= :error (:type second-event)))
    (is (not= (signature signature-key-a first-event)
              (signature signature-key-a second-event)))))

(deftest failure-signature-is-stable-keyed-and-non-plaintext
  (let [event (capture-event #(is (= "private-expected" "private-actual")))
        first-signature (signature signature-key-a event)
        repeated-signature (signature signature-key-a event)
        other-key-signature (signature signature-key-b event)]
    (is (re-matches #"[0-9a-f]{64}" first-signature))
    (is (= first-signature repeated-signature))
    (is (not= first-signature other-key-signature))
    (is (not (string/includes? first-signature "private")))))

(deftest runner-emits-one-private-signature-per-assertion-event
  (let [temporary-root (Files/createTempDirectory "sentinel-clj-event-test-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0))
        report-path (.resolve temporary-root "events.json")
        repository-root (Path/of (System/getProperty "user.dir") (make-array String 0))
        command [(str (.resolve repository-root "scripts/clojure-test-events.sh"))
                 "--sentinel-nonce" "fresh-nonce"
                 "--sentinel-signature-key" signature-key-a
                 "--sentinel-report" (str report-path)
                 "fixtures.runner.two-assertion-failures"]
        process (.start (ProcessBuilder. ^java.util.List command))]
    (try
      (is (true? (.waitFor process 30 TimeUnit/SECONDS)))
      (is (= 1 (.exitValue process)))
      (is (Files/isRegularFile report-path (make-array LinkOption 0)))
      (let [raw (when (Files/exists report-path (make-array LinkOption 0))
                  (Files/readString report-path))
            report (when raw (contract-json/read-path report-path))]
        (is (= "sentinel-clojure-test-events-v2" (:schemaVersion report)))
        (is (= ["fixtures.runner.two-assertion-failures/same-test"]
               (:failedTests report)))
        (is (= 2 (count (:failureSignatures report))))
        (is (= 2 (get-in report [:summary :fail])))
        (is (every? #(re-matches #"[0-9a-f]{64}" %)
                    (:failureSignatures report)))
        (is (not (string/includes? raw "private-expected")))
        (is (not (string/includes? raw "private-actual"))))
      (finally
        (.destroyForcibly process)
        (Files/deleteIfExists report-path)
        (Files/deleteIfExists temporary-root)))))

(deftest runner-selects-exact-test-vars-for-typed-replay
  (let [temporary-root (Files/createTempDirectory "sentinel-clj-selected-test-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0))
        report-path (.resolve temporary-root "events.json")
        repository-root (Path/of (System/getProperty "user.dir") (make-array String 0))
        selected-id "fixtures.runner.two-assertion-failures/same-test"
        command [(str (.resolve repository-root "scripts/clojure-test-events.sh"))
                 "--sentinel-nonce" "selected-nonce"
                 "--sentinel-signature-key" signature-key-a
                 "--sentinel-report" (str report-path)
                 "--sentinel-test-id" selected-id
                 "fixtures.runner.two-assertion-failures"]
        process (.start (ProcessBuilder. ^java.util.List command))]
    (try
      (is (true? (.waitFor process 30 TimeUnit/SECONDS)))
      (is (= 1 (.exitValue process)))
      (let [report (contract-json/read-path report-path)]
        (is (= [selected-id] (:tests report)))
        (is (= [selected-id] (:failedTests report)))
        (is (= {:test 1 :fail 2 :error 0} (:summary report))))
      (finally
        (.destroyForcibly process)
        (Files/deleteIfExists report-path)
        (Files/deleteIfExists temporary-root)))))

(deftest runner-rejects-unknown-and-duplicate-test-var-selectors
  (let [repository-root (Path/of (System/getProperty "user.dir") (make-array String 0))
        runner (str (.resolve repository-root "scripts/clojure-test-events.sh"))
        base [runner "--sentinel-nonce" "selected-nonce"
              "--sentinel-signature-key" signature-key-a]
        invalid-selections [["--sentinel-test-id" "fixtures.runner.two-assertion-failures/missing-test"]
                            ["--sentinel-test-id" "fixtures.runner.two-assertion-failures/same-test"
                             "--sentinel-test-id" "fixtures.runner.two-assertion-failures/same-test"]]]
    (doseq [selection invalid-selections]
      (let [temporary-root (Files/createTempDirectory "sentinel-clj-invalid-selection-"
                                                       (make-array java.nio.file.attribute.FileAttribute 0))
            report-path (.resolve temporary-root "events.json")
            command (vec (concat base ["--sentinel-report" (str report-path)] selection
                                 ["fixtures.runner.two-assertion-failures"]))
            process (.start (ProcessBuilder. ^java.util.List command))]
        (try
          (is (true? (.waitFor process 30 TimeUnit/SECONDS)))
          (is (not= 0 (.exitValue process)))
          (is (false? (Files/exists report-path (make-array LinkOption 0))))
          (finally
            (.destroyForcibly process)
            (delete-tree! temporary-root)))))))

(deftest runner-loads-project-test-resources
  (let [temporary-root (Files/createTempDirectory "sentinel-clj-resource-test-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0))
        report-path (.resolve temporary-root "events.json")
        repository-root (Path/of (System/getProperty "user.dir") (make-array String 0))
        command [(str (.resolve repository-root "scripts/clojure-test-events.sh"))
                 "--sentinel-nonce" "resource-nonce"
                 "--sentinel-signature-key" signature-key-a
                 "--sentinel-report" (str report-path)
                 "sentinel-clj.crap.formula-test"]
        process (.start (ProcessBuilder. ^java.util.List command))]
    (try
      (is (true? (.waitFor process 30 TimeUnit/SECONDS)))
      (is (= 0 (.exitValue process)))
      (let [report (contract-json/read-path report-path)]
        (is (= 3 (get-in report [:summary :test])))
        (is (= 0 (get-in report [:summary :fail])))
        (is (= 0 (get-in report [:summary :error]))))
      (finally
        (.destroyForcibly process)
        (Files/deleteIfExists report-path)
        (Files/deleteIfExists temporary-root)))))

(deftest runner-loads-mutated-project-source-before-install-source
  (let [project-root (Files/createTempDirectory "sentinel-clj-shadow-test-"
                                                (make-array java.nio.file.attribute.FileAttribute 0))
        report-path (.resolve project-root "events.json")
        repository-root (Path/of (System/getProperty "user.dir") (make-array String 0))
        command [(str (.resolve repository-root "scripts/clojure-test-events.sh"))
                 "--sentinel-nonce" "shadow-nonce"
                 "--sentinel-signature-key" signature-key-a
                 "--sentinel-report" (str report-path)
                 "self-probe-test"]]
    (write-file! (.resolve project-root "src/sentinel_clj/crap/formula.clj")
                 "(ns sentinel-clj.crap.formula)\n(defn source-origin [] :project)\n")
    (write-file! (.resolve project-root "test/self_probe_test.clj")
                 (str "(ns self-probe-test "
                      "(:require [clojure.test :refer [deftest is]] "
                      "[sentinel-clj.crap.formula :as formula]))\n"
                      "(deftest project-source-wins "
                      "(is (= :project (formula/source-origin))))\n"))
    (let [builder (doto (ProcessBuilder. ^java.util.List command)
                    (.directory (.toFile project-root)))
          process (.start builder)]
      (try
        (is (true? (.waitFor process 30 TimeUnit/SECONDS)))
        (is (= 0 (.exitValue process)))
        (let [report (contract-json/read-path report-path)]
          (is (= {:test 1 :fail 0 :error 0} (:summary report))))
        (finally
          (.destroyForcibly process)
          (delete-tree! project-root))))))
