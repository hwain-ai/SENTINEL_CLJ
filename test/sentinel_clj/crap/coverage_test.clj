(ns sentinel-clj.crap.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [sentinel-clj.crap.analyzer :as analyzer]
            [sentinel-clj.crap.coverage :as coverage]))

(def module-path "src/covered.clj")
(def source "(ns covered)\n(defn outer [x]\n  (if x\n    ((fn [y] (when y y)) x)\n    0))\n")

(defn- analyzed []
  (analyzer/analyze-source module-path source))

(defn- exact-form-report [rows]
  {:schema-version "sentinel-cloverage-form-v1"
   :basis :form
   :module-relative-path module-path
   :source-digest (:source-digest (first rows))
   :units (mapv (fn [row hits]
                  {:start-byte (get-in row [:source-range :start-byte])
                   :end-byte (get-in row [:source-range :end-byte])
                   :hits hits})
                rows
                [1 0])})

(defn- error-code [thunk]
  (:error (ex-data (try (thunk) (catch Exception error error)))))

(deftest form-coverage-belongs-only-to-the-closest-callable
  (let [rows (analyzed)
        joined (coverage/join rows (exact-form-report rows) :form)
        outer (first (filter #(= :defn (:kind %)) joined))
        child (first (filter #(= :fn (:kind %)) joined))]
    (is (= [1 1] [(:covered-units outer) (:total-units outer)]))
    (is (= [0 1] [(:covered-units child) (:total-units child)]))
    (is (= :known (:coverage-status outer)))
    (is (= :known (:coverage-status child)))
    (is (= "2" (:crap-raw outer)))
    (is (= "6" (:crap-raw child)))))

(deftest missing-stale-cross-file-and-basis-mismatch-never-fallback
  (let [rows (analyzed)
        report (exact-form-report rows)]
    (is (every? #(= :coverageFileMissing (:unknown-reason %))
                (coverage/join rows nil :form)))
    (is (= :coverageStale
           (error-code #(coverage/join rows (assoc report :source-digest (apply str (repeat 64 "0"))) :form))))
    (is (= :coverageModuleMismatch
           (error-code #(coverage/join rows (assoc report :module-relative-path "src/other.clj") :form))))
    (is (= :coverageBasisMismatch
           (error-code #(coverage/join rows report :line))))))

(deftest zero-form-units-are-unknown-not-perfect-coverage
  (let [rows (analyzed)
        joined (coverage/join rows (assoc (exact-form-report rows) :units []) :form)]
    (is (every? #(= :coverageUnitsMissing (:unknown-reason %)) joined))))

(deftest duplicate-or-partially-overlapping-form-units-are-rejected
  (let [rows (analyzed)
        report (exact-form-report rows)
        first-unit (first (:units report))]
    (is (= :coverageReportInvalid
           (error-code #(coverage/join rows (assoc report :units [first-unit first-unit]) :form))))
    (is (= :coverageReportInvalid
           (error-code #(coverage/join rows
                                       (assoc report :units [{:start-byte (inc (:start-byte first-unit))
                                                              :end-byte (inc (:end-byte first-unit))
                                                              :hits 1}])
                                       :form))))
    (is (= :coverageReportInvalid
           (error-code #(coverage/join rows
                                       (assoc report :units [{:start-byte (:source-byte-length (first rows))
                                                              :end-byte (inc (:source-byte-length (first rows)))
                                                              :hits 0}])
                                       :form))))))

(deftest lcov-line-basis-rejects-same-line-independent-functions-as-unknown
  (let [same-line-source "(ns same)\n{:left (fn [x] x) :right (fn [y] (inc y))}\n"
        rows (analyzer/analyze-source "src/same.clj" same-line-source)
        digest (:source-digest (first rows))
        lcov (str "TN:\nSF:src/same.clj\nDA:2,1\nLF:1\nLH:1\nend_of_record\n")
        joined (coverage/join-lcov rows lcov {:module-relative-path "src/same.clj"
                                              :source-digest digest})]
    (is (= 2 (count joined)))
    (is (every? #(= :coverageAmbiguous (:unknown-reason %)) joined))))

(deftest lcov-requires-exact-module-digest-and-summary
  (let [rows (analyzed)
        digest (:source-digest (first rows))
        valid (str "TN:\nSF:" module-path "\nDA:2,1\nDA:3,0\nLF:2\nLH:1\nend_of_record\n")]
    (is (= (count rows)
           (count (coverage/join-lcov rows valid {:module-relative-path module-path
                                                   :source-digest digest}))))
    (is (= :coverageReportInvalid
           (error-code #(coverage/join-lcov rows (clojure.string/replace valid "LH:1" "LH:2")
                                                 {:module-relative-path module-path
                                                  :source-digest digest}))))
    (is (= :coverageReportInvalid
           (error-code #(coverage/join-lcov rows
                                                 (str "TN:\nSF:" module-path
                                                      "\nLF:1\nDA:2,1\nLH:1\nend_of_record\n")
                                                 {:module-relative-path module-path
                                                  :source-digest digest}))))
    (is (= :coverageReportInvalid
           (error-code #(coverage/join-lcov rows
                                                 (str "TN:\nSF:" module-path
                                                      "\nDA:999,1\nLF:1\nLH:1\nend_of_record\n")
                                                 {:module-relative-path module-path
                                                  :source-digest digest}))))
    (is (= :coverageStale
           (error-code #(coverage/join-lcov rows valid
                                                 {:module-relative-path module-path
                                                  :source-digest (apply str (repeat 64 "0"))}))))))

(deftest multi-file-lcov-selects-one-exact-source-record-without-suffix-matching
  (let [rows (analyzed)
        digest (:source-digest (first rows))
        report (str "TN:\nSF:src/other.clj\nDA:1,1\nLF:1\nLH:1\nend_of_record\n"
                    "TN:\nSF:" module-path "\nDA:2,1\nLF:1\nLH:1\nend_of_record\n")]
    (is (= (count rows)
           (count (coverage/join-lcov rows report {:module-relative-path module-path
                                                    :source-digest digest}))))
    (is (= :coverageModuleMismatch
           (error-code #(coverage/join-lcov rows
                                                 "TN:\nSF:covered.clj\nDA:2,1\nLF:1\nLH:1\nend_of_record\n"
                                                 {:module-relative-path module-path
                                                  :source-digest digest}))))
    (is (= :coverageReportInvalid
           (error-code #(coverage/join-lcov rows (str report report)
                                                 {:module-relative-path module-path
                                                  :source-digest digest}))))))
