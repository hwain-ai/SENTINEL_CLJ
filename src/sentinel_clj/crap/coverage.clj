(ns sentinel-clj.crap.coverage
  (:require [clojure.string :as string]
            [sentinel-clj.crap.formula :as formula]
            [sentinel-clj.crap.models :as models]))

(def form-report-keys
  #{:schema-version :basis :module-relative-path :source-digest :units})

(def form-unit-keys #{:start-byte :end-byte :hits})
(def provenance-keys #{:module-relative-path :source-digest})

(defn- valid-digest? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- require-exact-keys [value expected error-code label]
  (when-not (and (map? value) (= expected (set (keys value))))
    (throw (models/contract-error error-code (str label " has missing or extra fields"))))
  value)

(defn- source-identity [rows]
  (when-not (vector? rows)
    (throw (models/contract-error :callableInventoryInvalid "callable inventory must be a vector")))
  (let [modules (set (map :module-relative-path rows))
        digests (set (map :source-digest rows))
        byte-lengths (set (map :source-byte-length rows))
        line-counts (set (map :source-line-count rows))]
    (when-not (and (= 1 (count modules)) (= 1 (count digests))
                   (= 1 (count byte-lengths)) (= 1 (count line-counts))
                   (valid-digest? (first digests)))
      (throw (models/contract-error :callableInventoryInvalid
                                    "callables must share one module and source digest")))
    {:module-relative-path (first modules)
     :source-digest (first digests)
     :source-byte-length (first byte-lengths)
     :source-line-count (first line-counts)}))

(defn- require-provenance [rows module-relative-path source-digest]
  (models/require-module-relative-path module-relative-path)
  (when-not (valid-digest? source-digest)
    (throw (models/contract-error :coverageReportInvalid "coverage source digest is invalid")))
  (let [source (source-identity rows)]
    (when-not (= (:module-relative-path source) module-relative-path)
      (throw (models/contract-error :coverageModuleMismatch
                                    "coverage module does not exactly match callable inventory")))
    (when-not (= (:source-digest source) source-digest)
      (throw (models/contract-error :coverageStale
                                    "coverage source digest does not match current source")))
    source))

(defn- validate-range [start end error-code label]
  (models/require-safe-integer start 0 error-code (str label ".start"))
  (models/require-safe-integer end 0 error-code (str label ".end"))
  (when-not (< start end)
    (throw (models/contract-error error-code (str label " must be a non-empty half-open range")))))

(defn- validate-unit [unit]
  (require-exact-keys unit form-unit-keys :coverageReportInvalid "coverage unit")
  (validate-range (:start-byte unit) (:end-byte unit) :coverageReportInvalid "coverage unit")
  (models/require-safe-integer (:hits unit) 0 :coverageReportInvalid "coverage unit hits")
  unit)

(defn- validate-callable [row]
  (when-not (and (map? row) (string? (:callable-id row))
                 (integer? (:cyclomatic-complexity row))
                 (map? (:source-range row))
                 (integer? (:source-byte-length row))
                 (integer? (:source-line-count row)))
    (throw (models/contract-error :callableInventoryInvalid "callable row is incomplete")))
  (validate-range (get-in row [:source-range :start-byte])
                  (get-in row [:source-range :end-byte])
                  :callableInventoryInvalid
                  "callable")
  (models/require-safe-integer (:source-byte-length row) 1 :callableInventoryInvalid "source byte length")
  (models/require-safe-integer (:source-line-count row) 1 :callableInventoryInvalid "source line count")
  row)

(defn- contains-unit? [row unit]
  (and (<= (get-in row [:source-range :start-byte]) (:start-byte unit))
       (>= (get-in row [:source-range :end-byte]) (:end-byte unit))))

(defn- intersects-unit? [row unit]
  (and (< (get-in row [:source-range :start-byte]) (:end-byte unit))
       (> (get-in row [:source-range :end-byte]) (:start-byte unit))))

(defn- range-size [row]
  (- (get-in row [:source-range :end-byte])
     (get-in row [:source-range :start-byte])))

(defn- closest-owner [rows unit]
  (let [containing (filter #(contains-unit? % unit) rows)
        smallest-size (when (seq containing) (apply min (map range-size containing)))
        closest (filter #(= smallest-size (range-size %)) containing)
        intersecting (filter #(intersects-unit? % unit) rows)]
    (cond
      (= 1 (count closest)) {:owner (:callable-id (first closest))}
      (> (count closest) 1) {:ambiguous (set (map :callable-id closest))}
      (seq intersecting) {:ambiguous (set (map :callable-id intersecting))}
      :else {})))

(defn- allocate-form-unit [allocation rows unit]
  (let [{:keys [owner ambiguous]} (closest-owner rows unit)]
    (cond
      owner (update-in allocation [:units owner] (fnil conj []) unit)
      (seq ambiguous) (update allocation :ambiguous into ambiguous)
      :else allocation)))

(defn- coverage-fraction [covered total]
  (let [divisor (.gcd (biginteger covered) (biginteger total))]
    {:numerator (quot covered divisor) :denominator (quot total divisor)}))

(defn- known-row [row basis units]
  (let [covered (count (filter #(pos? (:hits %)) units))
        total (count units)
        fraction (coverage-fraction covered total)
        crap (formula/calculate (:cyclomatic-complexity row) covered total)]
    (assoc row
           :coverage-status :known
           :coverage-basis basis
           :covered-units covered
           :total-units total
           :coverage-numerator (:numerator fraction)
           :coverage-denominator (:denominator fraction)
           :crap-numerator (:numerator crap)
           :crap-denominator (:denominator crap)
           :crap-raw (:decimal crap)
           :crap-pass? (:pass? crap))))

(defn- unknown-row [row basis reason]
  (assoc row :coverage-status :unknown :coverage-basis basis :unknown-reason reason))

(defn- finish-allocation [rows basis allocation]
  (mapv (fn [row]
          (let [callable-id (:callable-id row)
                units (get-in allocation [:units callable-id])]
            (cond
              ((:ambiguous allocation) callable-id) (unknown-row row basis :coverageAmbiguous)
              (empty? units) (unknown-row row basis :coverageUnitsMissing)
              :else (known-row row basis units))))
        rows))

(defn- allocate-form-units [rows units]
  (reduce #(allocate-form-unit %1 rows %2) {:units {} :ambiguous #{}} units))

(defn- reject-invalid-form-unit-inventory [rows units source-byte-length]
  (let [range-key (juxt :start-byte :end-byte)]
    (when (some #(> (count %) 1) (vals (group-by range-key units)))
      (throw (models/contract-error :coverageReportInvalid
                                    "Cloverage form unit range is duplicated")))
    (doseq [unit units]
      (cond
        (> (:end-byte unit) source-byte-length)
        (throw (models/contract-error :coverageReportInvalid
                                      "Cloverage form unit exceeds current source"))

        (and (not-any? #(contains-unit? % unit) rows)
             (some #(intersects-unit? % unit) rows))
        (throw (models/contract-error :coverageReportInvalid
                                      "Cloverage form unit crosses a callable boundary"))))))

(defn- missing-coverage [rows basis]
  (mapv #(unknown-row % basis :coverageFileMissing) rows))

(defn join [rows report expected-basis]
  (doseq [row rows] (validate-callable row))
  (when-not (#{:form :line} expected-basis)
    (throw (models/contract-error :coverageBasisInvalid "configured coverage basis is invalid")))
  (if (nil? report)
    (missing-coverage rows expected-basis)
    (do
      (require-exact-keys report form-report-keys :coverageReportInvalid "form coverage report")
      (when-not (and (= "sentinel-cloverage-form-v1" (:schema-version report))
                     (= :form (:basis report)))
        (throw (models/contract-error :coverageReportInvalid "unsupported Cloverage form report")))
      (when-not (= expected-basis (:basis report))
        (throw (models/contract-error :coverageBasisMismatch
                                      "configured basis and report basis differ")))
      (let [source (require-provenance rows (:module-relative-path report) (:source-digest report))]
        (when-not (vector? (:units report))
          (throw (models/contract-error :coverageReportInvalid "coverage units must be a vector")))
        (let [validated-units (mapv validate-unit (:units report))]
          (reject-invalid-form-unit-inventory rows validated-units (:source-byte-length source))
          (finish-allocation rows :form (allocate-form-units rows validated-units)))))))

(defn- parse-safe-count [text minimum]
  (when-not (re-matches #"(?:0|[1-9][0-9]*)" text)
    (throw (models/contract-error :coverageReportInvalid "LCOV count is not canonical")))
  (models/require-safe-integer (bigint text) minimum :coverageReportInvalid "LCOV count"))

(defn- parse-da [line]
  (let [[_ line-number hits] (re-matches #"DA:([0-9]+),([0-9]+)" line)]
    (when-not line-number
      (throw (models/contract-error :coverageReportInvalid "LCOV DA record is invalid")))
    {:line (parse-safe-count line-number 1)
     :hits (parse-safe-count hits 0)}))

(defn- unique-line [lines prefix]
  (let [matches (filter #(string/starts-with? % prefix) lines)]
    (when-not (= 1 (count matches))
      (throw (models/contract-error :coverageReportInvalid (str "LCOV requires one " prefix " record"))))
    (first matches)))

(defn- parse-summary [lines prefix]
  (let [line (unique-line lines prefix)
        text (subs line (count prefix))]
    (parse-safe-count text 0)))

(defn- parse-lcov-lines [text]
  (when-not (and (models/unicode-scalar-string? text) (string/ends-with? text "\n"))
    (throw (models/contract-error :coverageReportInvalid "LCOV must be valid text ending in LF")))
  (let [lines (vec (butlast (string/split text #"\n" -1)))
        allowed? #(or (= % "TN:") (= % "end_of_record")
                      (re-matches #"SF:.+" %)
                      (re-matches #"DA:[0-9]+,[0-9]+" %)
                      (re-matches #"LF:[0-9]+" %)
                      (re-matches #"LH:[0-9]+" %))]
    (when (or (some string/blank? (remove #(= % "TN:") lines))
              (not-every? allowed? lines)
              (not= "end_of_record" (last lines)))
      (throw (models/contract-error :coverageReportInvalid "LCOV contains unsupported or misplaced records")))
    lines))

(defn- split-lcov-records [lines]
  (let [{:keys [current records]}
        (reduce (fn [{:keys [current records]} line]
                  (if (= "end_of_record" line)
                    {:current [] :records (conj records (conj current line))}
                    {:current (conj current line) :records records}))
                {:current [] :records []}
                lines)]
    (when (or (seq current) (empty? records) (some #(= ["end_of_record"] %) records))
      (throw (models/contract-error :coverageReportInvalid "LCOV record boundary is invalid")))
    records))

(defn- valid-lcov-order? [lines]
  (let [without-title (if (= "TN:" (first lines)) (subvec lines 1) lines)
        middle (subvec without-title 1 (- (count without-title) 3))]
    (and (<= 4 (count without-title))
         (string/starts-with? (first without-title) "SF:")
         (every? #(string/starts-with? % "DA:") middle)
         (string/starts-with? (nth without-title (- (count without-title) 3)) "LF:")
         (string/starts-with? (nth without-title (- (count without-title) 2)) "LH:")
         (= "end_of_record" (last without-title)))))

(defn- parse-lcov-record [lines]
  (let [source-line (unique-line lines "SF:")
        units (mapv parse-da (filter #(string/starts-with? % "DA:") lines))
        line-numbers (map :line units)
        expected-total (parse-summary lines "LF:")
        expected-covered (parse-summary lines "LH:")]
    (when-not (and (valid-lcov-order? lines)
                   (<= (count (filter #(= % "TN:") lines)) 1)
                   (= (count units) (count (set line-numbers)))
                   (= expected-total (count units))
                   (= expected-covered (count (filter #(pos? (:hits %)) units))))
      (throw (models/contract-error :coverageReportInvalid "LCOV summary or DA inventory mismatch")))
    (let [module-relative-path (subs source-line 3)]
      (try
        (models/require-module-relative-path module-relative-path)
        (catch Exception error
          (throw (ex-info "LCOV source path is invalid"
                          {:error :coverageReportInvalid}
                          error))))
      {:module-relative-path module-relative-path :units units})))

(defn- parse-lcov [text]
  (let [records (mapv parse-lcov-record
                      (split-lcov-records (parse-lcov-lines text)))
        paths (map :module-relative-path records)]
    (when-not (= (count paths) (count (set paths)))
      (throw (models/contract-error :coverageReportInvalid "LCOV source record is duplicated")))
    records))

(defn- covers-line? [row unit]
  (<= (get-in row [:source-range :start-line])
      (:line unit)
      (get-in row [:source-range :end-line])))

(defn- allocate-line-unit [allocation rows unit]
  (let [owners (filter #(covers-line? % unit) rows)
        callable-ids (set (map :callable-id owners))
        coverage-unit {:hits (:hits unit)}]
    (cond
      (= 1 (count owners)) (update-in allocation [:units (:callable-id (first owners))]
                                      (fnil conj []) coverage-unit)
      (> (count owners) 1) (update allocation :ambiguous into callable-ids)
      :else allocation)))

(defn- allocate-line-units [rows units]
  (reduce #(allocate-line-unit %1 rows %2) {:units {} :ambiguous #{}} units))

(defn- require-lines-within-source [units source-line-count]
  (when (some #(< source-line-count (:line %)) units)
    (throw (models/contract-error :coverageReportInvalid
                                  "LCOV line exceeds current source"))))

(defn join-lcov [rows text provenance]
  (doseq [row rows] (validate-callable row))
  (require-exact-keys provenance provenance-keys :coverageReportInvalid "LCOV provenance")
  (models/require-module-relative-path (:module-relative-path provenance))
  (let [reports (parse-lcov text)
        matches (filter #(= (:module-relative-path provenance)
                            (:module-relative-path %))
                        reports)]
    (when-not (= 1 (count matches))
      (throw (models/contract-error :coverageModuleMismatch
                                    "LCOV has no exact source record for the provenance module")))
    (let [source (require-provenance rows (:module-relative-path provenance) (:source-digest provenance))
          units (:units (first matches))]
      (require-lines-within-source units (:source-line-count source))
      (finish-allocation rows :line (allocate-line-units rows units)))))
