(ns sentinel-clj.quality
  (:require [sentinel-clj.config :as config]
            [sentinel-clj.crap.analyzer :as analyzer]
            [sentinel-clj.crap.coverage :as coverage]
            [sentinel-clj.crap.models :as models]
            [sentinel-clj.json :as contract-json]
            [sentinel-clj.mutation.clj-mutate-bridge :as bridge])
  (:import [java.nio.file Files Path]))

(defn- relative-path [project ^Path path]
  (str (.relativize ^Path (:module-root project) path)))

(defn- analyze-file [project ^Path path]
  (analyzer/analyze-bytes (relative-path project path) (Files/readAllBytes path)))

(def single-coverage-keys
  #{:schemaVersion :basis :moduleRelativePath :sourceDigest :units})
(def multiple-coverage-keys #{:schemaVersion :basis :files})
(def coverage-file-keys #{:moduleRelativePath :sourceDigest :units})
(def coverage-unit-keys #{:startByte :endByte :hits})

(defn- coverage-error [message]
  (throw (ex-info message {:error :coverageReportInvalid :exit-code 5})))

(defn- require-wire-keys [value expected label]
  (when-not (and (map? value) (= expected (set (keys value))))
    (coverage-error (str label " has missing or extra fields")))
  value)

(defn- normalize-unit [unit]
  (require-wire-keys unit coverage-unit-keys "coverage unit")
  {:start-byte (:startByte unit) :end-byte (:endByte unit) :hits (:hits unit)})

(defn- require-report-identity [schema-version basis]
  (when-not (and (= "sentinel-cloverage-form-v1" schema-version)
                 (= "form" basis))
    (coverage-error "coverage report identity is unsupported")))

(defn- normalize-file-report [schema-version basis report]
  (require-report-identity schema-version basis)
  (require-wire-keys report coverage-file-keys "coverage file")
  (when-not (vector? (:units report))
    (coverage-error "coverage file units must be an array"))
  {:schema-version schema-version
   :basis (keyword basis)
   :module-relative-path (:moduleRelativePath report)
   :source-digest (:sourceDigest report)
   :units (mapv normalize-unit (:units report))})

(defn- normalize-single-report [report]
  (normalize-file-report (:schemaVersion report) (:basis report)
                         (select-keys report coverage-file-keys)))

(defn- normalize-multiple-report [report]
  (require-report-identity (:schemaVersion report) (:basis report))
  (when-not (vector? (:files report))
    (coverage-error "coverage files must be an array"))
  (mapv #(normalize-file-report (:schemaVersion report) (:basis report) %)
        (:files report)))

(defn- normalize-coverage [report]
  (let [keys (when (map? report) (set (keys report)))]
    (cond
      (= single-coverage-keys keys) [(normalize-single-report report)]
      (= multiple-coverage-keys keys) (normalize-multiple-report report)
      :else (coverage-error "coverage report has missing or extra fields"))))

(defn- analyzed-file [project path]
  {:module-relative-path (relative-path project path)
   :rows (analyze-file project path)})

(defn- require-report-scope [analyzed reports]
  (let [expected (set (map :module-relative-path analyzed))
        paths (map :module-relative-path reports)]
    (when-not (= (count paths) (count (set paths)))
      (coverage-error "coverage report contains a duplicate file"))
    (when (seq (remove expected paths))
      (coverage-error "coverage report contains a file outside production scope"))))

(defn- measure-file [reports-by-path analyzed]
  (coverage/join (:rows analyzed)
                 (get reports-by-path (:module-relative-path analyzed))
                 :form))

(defn- riskier? [left right]
  (> (* (:crap-numerator left) (:crap-denominator right))
     (* (:crap-numerator right) (:crap-denominator left))))

(defn- maximum-known [rows]
  (reduce (fn [current row]
            (if (or (nil? current) (riskier? row current)) row current))
          nil
          (filter #(= :known (:coverage-status %)) rows)))

(defn- crap-findings [rows]
  (mapv (fn [row]
          {:stable-id (:callable-id row)
           :finding-class (if (= :unknown (:coverage-status row)) "environment" "projectCode")
           :defect-kind (if (= :unknown (:coverage-status row))
                          (name (:unknown-reason row))
                          "crapAboveEight")
           :status "failed"})
        (filter #(or (= :unknown (:coverage-status %)) (false? (:crap-pass? %))) rows)))

(defn run-crap [project]
  (let [analyzed (mapv #(analyzed-file project %) (:production-files project))
        reports (-> (config/resolve-report project :coverage) contract-json/read-path normalize-coverage)
        _ (require-report-scope analyzed reports)
        reports-by-path (into {} (map (juxt :module-relative-path identity) reports))
        measured (vec (mapcat #(measure-file reports-by-path %) analyzed))
        maximum (maximum-known measured)
        findings (crap-findings measured)
        unknown-count (count (filter #(= :unknown (:coverage-status %)) measured))]
    {:public {:pass (empty? findings)
              :callableCount (count measured)
              :unknownCount unknown-count
              :maxNumerator (if maximum (str (:crap-numerator maximum)) "0")
              :maxDenominator (if maximum (str (:crap-denominator maximum)) "1")}
     :findings findings}))

(defn run-mutation [project]
  (let [source-inventory (mapv (fn [path]
                                 {:moduleRelativePath (relative-path project path)
                                  :sourceDigest (models/sha256-bytes (Files/readAllBytes path))})
                               (:production-files project))
        report (contract-json/read-path (config/resolve-report project :mutation))
        normalized (bridge/normalize-report report source-inventory)
        gate (:gate normalized)
        records (:records normalized)
        findings (mapv (fn [record]
                         {:stable-id (:candidate-id record)
                          :finding-class (if (#{:survived :uncovered} (:status record))
                                           "projectTest"
                                           "projectCodeOrTest")
                          :defect-kind (name (:status record))
                          :status (name (:status record))})
                       (remove #(= :killed (:status %)) records))]
    {:public (merge {:pass (:pass? gate)
                     :inScope (:in-scope gate)
                     :unauthorizedExclusion (:unauthorized-exclusion gate)}
                    (:counts gate))
     :findings findings}))
