(ns sentinel-clj.evidence.contract
  (:require [clojure.string :as string])
  (:import [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

(def max-safe-integer 9007199254740991)
(def max-uint64 18446744073709551615N)

(def evidence-body-keys
  #{:certification :command :commitSequence :committedAtUtc :completedAtUtc
    :components :correlationId :diagnosticCodes :eventCount :events :exitCode
    :fingerprintVersion :keyEpoch :language :mode :observationSource
    :projectStateHmac :runId :schemaVersion :sourceRunId :specVersion
    :startedAtUtc :startedSha256 :terminalStatus})
(def evidence-keys (conj evidence-body-keys :hmacSha256))
(def crap-keys #{:callableCount :maxNumerator :maxDenominator :pass :unknownCount})
(def mutation-states
  [:killed :survived :uncovered :timedOut :compileError
   :runtimeError :pending :ignored :toolError])
(def mutation-keys (conj (set mutation-states) :inScope :unauthorizedExclusion :pass))

(def terminal-exits
  {"passed" 0
   "toolError" 1
   "qualityFailed" 2
   "baselineFailed" 4
   "dependencyError" 5
   "backendError" 6
   "evidenceError" 7
   "cancelled" 8})

(def utc-pattern
  #"^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]{1,9}))?Z$")
(def timestamp-formatter
  (.withZone (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss") ZoneOffset/UTC))

(defn- fail! [message]
  (throw (ex-info message {:error :evidenceError :exit-code 7})))

(defn- bool? [value]
  (or (true? value) (false? value)))

(defn- safe-count? [value]
  (and (integer? value) (<= 0 value max-safe-integer)))

(defn- positive-safe-integer? [value]
  (and (integer? value) (<= 1 value max-safe-integer)))

(defn- valid-hex? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- valid-uuid? [value]
  (try
    (and (string? value)
         (boolean (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                              value))
         (= value (str (UUID/fromString value))))
    (catch Exception _ false)))

(defn- parse-uint64! [value]
  (when-not (and (string? value) (re-matches #"[1-9][0-9]*" value))
    (fail! "commit sequence is invalid"))
  (let [parsed (bigint value)]
    (when (> parsed max-uint64)
      (fail! "commit sequence is invalid"))
    parsed))

(defn canonical-timestamp [^Instant instant]
  (let [base (.format timestamp-formatter instant)
        fraction (string/replace (format "%09d" (.getNano instant)) #"0+$" "")]
    (str base (when (seq fraction) (str "." fraction)) "Z")))

(defn- canonical-time-match? [match]
  (let [year (nth match 1)
        fraction (nth match 7)]
    (and (not= "0000" year)
         (not (and fraction (string/ends-with? fraction "0"))))))

(defn- parse-timestamp! [value]
  (let [match (when (string? value) (re-matches utc-pattern value))]
    (when-not (and match (canonical-time-match? match))
      (fail! "UTC timestamp is invalid or noncanonical"))
    (try
      (Instant/parse value)
      (catch Exception _ (fail! "UTC timestamp is invalid")))))

(defn- decimal! [value positive? max-length]
  (let [pattern (if positive? #"[1-9][0-9]*" #"(?:0|[1-9][0-9]*)")]
    (when-not (and (string? value)
                   (<= (count value) max-length)
                   (re-matches pattern value))
      (fail! "CRAP fraction is invalid"))
    (bigint value)))

(defn- validate-crap-counts! [component]
  (let [callable-count (:callableCount component)
        unknown-count (:unknownCount component)]
    (when-not (and (safe-count? callable-count)
                   (safe-count? unknown-count)
                   (<= unknown-count callable-count)
                   (bool? (:pass component)))
      (fail! "CRAP component value is invalid"))))

(defn- validate-crap-sentinel! [component numerator denominator]
  (let [all-unknown (= (:callableCount component) (:unknownCount component))
        sentinel (and (zero? numerator) (= 1N denominator))]
    (when-not (= all-unknown sentinel)
      (fail! "CRAP all-unknown sentinel is invalid"))))

(defn- validate-crap-pass! [component numerator denominator]
  (let [expected (and (pos? (:callableCount component))
                      (zero? (:unknownCount component))
                      (<= numerator (*' 8 denominator)))]
    (when-not (= expected (:pass component))
      (fail! "CRAP pass value is invalid"))))

(defn- validate-crap! [component]
  (when-not (and (map? component) (= crap-keys (set (keys component))))
    (fail! "CRAP component shape is invalid"))
  (let [numerator (decimal! (:maxNumerator component) false 96)
        denominator (decimal! (:maxDenominator component) true 48)]
    (when-not (= 1N (.gcd (biginteger numerator) (biginteger denominator)))
      (fail! "CRAP fraction is not reduced"))
    (validate-crap-counts! component)
    (validate-crap-sentinel! component numerator denominator)
    (validate-crap-pass! component numerator denominator)))

(defn- validate-mutation! [component]
  (when-not (and (map? component) (= mutation-keys (set (keys component))))
    (fail! "mutation component shape is invalid"))
  (let [counts (mapv component mutation-states)
        in-scope (:inScope component)
        unauthorized (:unauthorizedExclusion component)]
    (when-not (and (every? safe-count? counts)
                   (safe-count? in-scope)
                   (safe-count? unauthorized)
                   (bool? (:pass component)))
      (fail! "mutation component value is invalid"))
    (when-not (= in-scope (reduce + counts))
      (fail! "mutation counts do not sum to inScope"))
    (let [expected (and (pos? in-scope)
                        (= in-scope (:killed component))
                        (every? zero? (rest counts))
                        (zero? unauthorized))]
      (when-not (= expected (:pass component))
        (fail! "mutation pass value is invalid")))))

(defn- expected-component-keys [command]
  (case command
    "crap" #{:crap}
    "mutation" #{:mutation}
    "check" #{:crap :mutation}
    (fail! "evidence command is invalid")))

(defn- validate-components! [command components]
  (when-not (and (map? components)
                 (= (expected-component-keys command) (set (keys components))))
    (fail! "evidence components are invalid"))
  (when-let [crap (:crap components)] (validate-crap! crap))
  (when-let [mutation (:mutation components)] (validate-mutation! mutation))
  (every? true? (map :pass (vals components))))

(defn- validate-source! [body]
  (let [source (:sourceRunId body)]
    (if (= "fresh" (:observationSource body))
      (when-not (nil? source) (fail! "fresh evidence has a source run"))
      (when-not (and (valid-uuid? source) (not= source (:runId body)))
        (fail! "cached evidence source run is invalid")))))

(defn- validate-schema-identity! [body]
  (when-not (= "sentinel-evidence-v1" (:schemaVersion body))
    (fail! "evidence schema version is invalid"))
  (when-not (and (string? (:specVersion body))
                 (re-matches #"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?"
                             (:specVersion body)))
    (fail! "spec version is invalid"))
  (when-not (= "sentinel-fingerprint-v1" (:fingerprintVersion body))
    (fail! "fingerprint version is invalid"))
  (when-not (and (valid-uuid? (:runId body)) (valid-uuid? (:correlationId body)))
    (fail! "evidence UUID is invalid")))

(defn- validate-enums! [body]
  (expected-component-keys (:command body))
  (when-not (contains? #{"python" "typescript" "go" "java" "clojure"}
                       (:language body))
    (fail! "evidence language is invalid"))
  (when-not (contains? #{"strict" "local"} (:mode body))
    (fail! "evidence mode is invalid"))
  (when-not (contains? #{"fresh" "cache"} (:observationSource body))
    (fail! "observation source is invalid")))

(defn- validate-evidence-digests! [body]
  (parse-uint64! (:commitSequence body))
  (when-not (positive-safe-integer? (:keyEpoch body))
    (fail! "evidence key epoch is invalid"))
  (when-not (and (valid-hex? (:projectStateHmac body))
                 (valid-hex? (:startedSha256 body)))
    (fail! "evidence digest is invalid")))

(defn- validate-identity! [body]
  (validate-schema-identity! body)
  (validate-enums! body)
  (validate-evidence-digests! body)
  (validate-source! body)
  (when (and (= "strict" (:mode body)) (= "cache" (:observationSource body)))
    (fail! "strict evidence cannot use cache")))

(defn- validate-times! [body]
  (let [started (parse-timestamp! (:startedAtUtc body))
        completed (parse-timestamp! (:completedAtUtc body))
        committed (parse-timestamp! (:committedAtUtc body))]
    (when-not (and (not (pos? (compare started completed)))
                   (not (pos? (compare completed committed))))
      (fail! "evidence timestamps are out of order"))))

(defn- validate-terminal-pair! [body]
  (when-not (= (:exitCode body) (get terminal-exits (:terminalStatus body) ::missing))
    (fail! "terminal status and exit code differ")))

(defn- validate-tool-precedence! [body]
  (let [mutation (get-in body [:components :mutation])]
    (when (and mutation
               (pos? (:toolError mutation))
               (not= "backendError" (:terminalStatus body)))
      (fail! "tool error did not take terminal precedence"))))

(defn- validate-terminal-components! [status components-pass]
  (when (and (= "passed" status) (not components-pass))
    (fail! "passed terminal has a failing component"))
  (when (and (= "qualityFailed" status) components-pass)
    (fail! "quality failure has no failing component")))

(defn- validate-certification! [body components-pass]
  (let [expected (and (= "strict" (:mode body))
                      (= "fresh" (:observationSource body))
                      (= "passed" (:terminalStatus body))
                      components-pass)]
    (when-not (and (bool? (:certification body))
                   (= expected (:certification body)))
      (fail! "certification is invalid"))))

(defn- validate-terminal! [body components-pass]
  (validate-terminal-pair! body)
  (validate-tool-precedence! body)
  (validate-terminal-components! (:terminalStatus body) components-pass)
  (validate-certification! body components-pass))

(defn- validate-event! [entry index]
  (when-not (and (map? entry) (= #{:filename :sha256} (set (keys entry))))
    (fail! "event manifest entry shape is invalid"))
  (when-not (= (format "%032x.json" index) (:filename entry))
    (fail! "event manifest ordinal is invalid"))
  (when-not (valid-hex? (:sha256 entry))
    (fail! "event manifest digest is invalid"))
  (:filename entry))

(defn- validate-manifest! [body]
  (let [event-count (:eventCount body)
        events (:events body)]
    (when-not (and (safe-count? event-count) (vector? events))
      (fail! "event manifest is invalid"))
    (when-not (= event-count (count events))
      (fail! "event manifest count is invalid"))
    (let [names (mapv validate-event! events (iterate inc 1))]
      (when-not (= (count names) (count (set names)))
        (fail! "event manifest contains duplicates")))
    (when (and (= "cache" (:observationSource body)) (seq events))
      (fail! "cached evidence cannot contain events"))))

(defn- validate-diagnostics! [diagnostics]
  (when-not (and (vector? diagnostics)
                 (every? #(and (string? %)
                               (re-matches #"[a-z][A-Za-z0-9]{0,63}" %))
                         diagnostics)
                 (= diagnostics (vec (sort (distinct diagnostics)))))
    (fail! "diagnostic codes are invalid")))

(defn validate-body! [body current-epoch expected-project-hmac historical?]
  (when-not (and (map? body) (= evidence-body-keys (set (keys body))))
    (fail! "evidence body shape is invalid"))
  (validate-identity! body)
  (validate-times! body)
  (validate-terminal! body (validate-components! (:command body) (:components body)))
  (validate-manifest! body)
  (validate-diagnostics! (:diagnosticCodes body))
  (when-not (if historical?
              (<= (:keyEpoch body) current-epoch)
              (= (:keyEpoch body) current-epoch))
    (fail! "evidence key epoch is invalid"))
  (when-not (= expected-project-hmac (:projectStateHmac body))
    (fail! "project state binding is invalid"))
  body)

(defn terminal-status [exit-code]
  (or (some (fn [[status code]] (when (= code exit-code) status)) terminal-exits)
      (fail! "terminal exit code is invalid")))

(defn certification [mode terminal-status components]
  (and (= "strict" mode)
       (= "passed" terminal-status)
       (every? true? (map :pass (vals components)))))

(defn empty-crap-component []
  {:callableCount 0 :maxNumerator "0" :maxDenominator "1"
   :pass false :unknownCount 0})

(defn empty-mutation-component []
  {:inScope 0 :killed 0 :survived 0 :uncovered 0 :timedOut 0
   :compileError 0 :runtimeError 0 :pending 0 :ignored 0 :toolError 0
   :unauthorizedExclusion 0 :pass false})

(defn empty-components [command]
  (case command
    "crap" {:crap (empty-crap-component)}
    "mutation" {:mutation (empty-mutation-component)}
    "check" {:crap (empty-crap-component) :mutation (empty-mutation-component)}
    (fail! "evidence command is invalid")))
