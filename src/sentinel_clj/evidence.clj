(ns sentinel-clj.evidence
  (:require [sentinel-clj.evidence.contract :as contract]
            [sentinel-clj.json :as contract-json])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission PosixFilePermissions]
           [java.security MessageDigest SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def owner-directory
  #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE PosixFilePermission/OWNER_EXECUTE})
(def owner-file #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
(def max-uint64 18446744073709551615N)
(def max-safe-integer 9007199254740991)

(def sequence-key-domain "SENTINEL\u0000commit-sequence-key\u0000v1\u0000")
(def sequence-record-domain "SENTINEL\u0000commit-sequence\u0000v1\u0000")
(def evidence-key-domain "SENTINEL\u0000evidence-key\u0000v1\u0000")
(def evidence-record-domain "SENTINEL\u0000evidence\u0000v1\u0000")
(def project-state-domain "SENTINEL\u0000project-state-binding\u0000v1\u0000")

(def control-keys
  #{:schemaVersion :stateVersion :projectIdentifier :fingerprintHmacKey
    :cleanupLeaseKey :keyEpoch})
(def sequence-keys #{:version :lastAllocated :hmacSha256})
(def started-keys
  #{:schemaVersion :runId :correlationId :command :language :startedAtUtc})

(defn- fail-evidence [message]
  (throw (ex-info message {:error :evidenceError :exit-code 7})))

(defn- exists? [^Path path]
  (Files/exists path (make-array LinkOption 0)))

(defn- directory? [^Path path]
  (Files/isDirectory path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- regular-file? [^Path path]
  (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- mkdir [^Path path]
  (Files/createDirectories path (make-array FileAttribute 0))
  (Files/setPosixFilePermissions path owner-directory)
  path)

(defn- owner-file-attribute []
  (PosixFilePermissions/asFileAttribute owner-file))

(defn- create-owner-file [^Path path]
  (Files/createFile path (into-array FileAttribute [(owner-file-attribute)])))

(defn- force-directory [^Path path]
  (with-open [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))]
    (.force channel true)))

(defn- write-new [^Path path ^bytes content]
  (create-owner-file path)
  (with-open [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/WRITE]))]
    (.write channel (ByteBuffer/wrap content))
    (.force channel true))
  path)

(defn- atomic-replace [^Path path value]
  (let [temporary (.resolveSibling path (str "." (.getFileName path) "." (UUID/randomUUID) ".tmp"))]
    (write-new temporary (contract-json/write-canonical-bytes value))
    (Files/move temporary path (into-array java.nio.file.CopyOption
                                           [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
    (force-directory (.getParent path))
    path))

(defn- random-base64url [size]
  (let [content (byte-array size)]
    (.nextBytes (SecureRandom.) content)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) content)))

(defn- new-control []
  {:schemaVersion "sentinel-project-state-v1"
   :stateVersion "state-v1"
   :projectIdentifier (random-base64url 16)
   :fingerprintHmacKey (random-base64url 32)
   :cleanupLeaseKey (random-base64url 32)
   :keyEpoch 1})

(defn- decode-sized [value size]
  (try
    (when-not (and (string? value)
                   (= (quot (+ (* size 8) 5) 6) (count value))
                   (re-matches #"[A-Za-z0-9_-]+" value))
      (fail-evidence "project control key encoding is invalid"))
    (let [decoded (.decode (Base64/getUrlDecoder) ^String value)
          canonical (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) decoded)]
      (when-not (= size (alength decoded))
        (fail-evidence "project control key length is invalid"))
      (when-not (= value canonical)
        (fail-evidence "project control key encoding is not canonical"))
      decoded)
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception _ (fail-evidence "project control key is invalid"))))

(defn- validate-control! [control]
  (when-not (= control-keys (set (keys control)))
    (fail-evidence "project control shape is invalid"))
  (when-not (= "sentinel-project-state-v1" (:schemaVersion control))
    (fail-evidence "project control version is invalid"))
  (when-not (= "state-v1" (:stateVersion control))
    (fail-evidence "project state version is invalid"))
  (when-not (and (integer? (:keyEpoch control))
                 (<= 1 (:keyEpoch control) max-safe-integer))
    (fail-evidence "project control epoch is invalid"))
  (decode-sized (:projectIdentifier control) 16)
  (let [fingerprint-key (decode-sized (:fingerprintHmacKey control) 32)
        cleanup-key (decode-sized (:cleanupLeaseKey control) 32)]
    (when (MessageDigest/isEqual fingerprint-key cleanup-key)
      (fail-evidence "project control keys are not separated")))
  control)

(defn- read-canonical! [^Path path]
  (try
    (contract-json/read-canonical-path path)
    (catch clojure.lang.ExceptionInfo error
      (if (= :evidenceError (:error (ex-data error)))
        (throw error)
        (fail-evidence "state JSON is not canonical")))
    (catch Exception _ (fail-evidence "state JSON could not be read"))))

(defn- read-control! [^Path state-root]
  (let [path (.resolve state-root "project.json")]
    (when-not (regular-file? path)
      (fail-evidence "project control is missing"))
    (validate-control! (read-canonical! path))))

(defn- utf8-bytes [^String value]
  (.getBytes value StandardCharsets/UTF_8))

(defn- joined-bytes [^String prefix ^bytes content]
  (let [prefix-bytes (utf8-bytes prefix)
        joined (byte-array (+ (alength prefix-bytes) (alength content)))]
    (System/arraycopy prefix-bytes 0 joined 0 (alength prefix-bytes))
    (System/arraycopy content 0 joined (alength prefix-bytes) (alength content))
    joined))

(defn- hmac-bytes [^bytes key ^bytes content]
  (try
    (let [mac (Mac/getInstance "HmacSHA256")]
      (.init mac (SecretKeySpec. key "HmacSHA256"))
      (.doFinal mac content))
    (catch Exception _ (fail-evidence "HMAC operation failed"))))

(defn- hex [^bytes content]
  (apply str (map #(format "%02x" (bit-and 0xff %)) content)))

(defn- derived-key [control domain]
  (hmac-bytes (decode-sized (:cleanupLeaseKey control) 32) (utf8-bytes domain)))

(defn- record-hmac [^bytes key domain body]
  (hex (hmac-bytes key (joined-bytes domain
                                     (contract-json/write-canonical-value-bytes body)))))

(defn- sign-record [key domain body]
  (assoc body :hmacSha256 (record-hmac key domain body)))

(defn- valid-hmac? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- verify-record! [record key domain]
  (let [actual (:hmacSha256 record)
        body (dissoc record :hmacSha256)]
    (when-not (valid-hmac? actual)
      (fail-evidence "state HMAC shape is invalid"))
    (let [expected (record-hmac key domain body)]
      (when-not (MessageDigest/isEqual (utf8-bytes actual) (utf8-bytes expected))
        (fail-evidence "state HMAC is invalid")))
    body))

(defn- project-state-hmac [control]
  (hex (hmac-bytes (decode-sized (:cleanupLeaseKey control) 32)
                   (joined-bytes project-state-domain
                                 (decode-sized (:projectIdentifier control) 16)))))

(defn- sha256 [content]
  (hex (.digest (MessageDigest/getInstance "SHA-256") ^bytes content)))

(defn- parse-uint64 [value allow-zero?]
  (when-not (and (string? value) (re-matches #"(?:0|[1-9][0-9]*)" value))
    (fail-evidence "commit sequence is not canonical"))
  (let [parsed (bigint value)]
    (when-not (and (<= parsed max-uint64) (or allow-zero? (pos? parsed)))
      (fail-evidence "commit sequence is out of range"))
    parsed))

(defn- read-sequence! [^Path state-root control]
  (let [path (.resolve state-root "commit-sequence.json")]
    (when (exists? path)
      (when-not (regular-file? path)
        (fail-evidence "commit sequence is not a regular file"))
      (let [record (read-canonical! path)]
        (when-not (= sequence-keys (set (keys record)))
          (fail-evidence "commit sequence shape is invalid"))
        (when-not (= "commit-sequence-v1" (:version record))
          (fail-evidence "commit sequence version is invalid"))
        (verify-record! record (derived-key control sequence-key-domain)
                        sequence-record-domain)
        (parse-uint64 (:lastAllocated record) false)))))

(defn- allocate-sequence! [^Path state-root control current]
  (when (= max-uint64 current)
    (fail-evidence "commit sequence is exhausted"))
  (let [next-value (inc current)
        body {:version "commit-sequence-v1" :lastAllocated (str next-value)}
        record (sign-record (derived-key control sequence-key-domain)
                            sequence-record-domain body)]
    (atomic-replace (.resolve state-root "commit-sequence.json") record)
    next-value))

(defn- evidence-paths [^Path state-root]
  (let [runs-root (.resolve state-root "runs")]
    (when-not (directory? runs-root)
      (fail-evidence "run store is missing"))
    (with-open [paths (Files/list runs-root)]
      (->> (iterator-seq (.iterator paths))
           (sort-by str)
           (keep (fn [run-root]
                   (when-not (directory? run-root)
                     (fail-evidence "run entry is not a directory"))
                   (let [path (.resolve ^Path run-root "evidence.json")]
                     (when (exists? path)
                       (when-not (regular-file? path)
                         (fail-evidence "evidence is not a regular file"))
                       path))))
           vec))))

(defn- valid-run-id? [value]
  (try
    (and (string? value) (= value (str (UUID/fromString value))))
    (catch Exception _ false)))

(defn- validate-started! [^Path evidence-path evidence]
  (let [started-path (.resolve (.getParent evidence-path) "started.json")]
    (when-not (regular-file? started-path)
      (fail-evidence "completed run has no started record"))
    (let [started (read-canonical! started-path)
          joined-keys [:runId :correlationId :command :language :startedAtUtc]]
      (when-not (= started-keys (set (keys started)))
        (fail-evidence "started record shape is invalid"))
      (when-not (= "sentinel-started-v1" (:schemaVersion started))
        (fail-evidence "started record version is invalid"))
      (when-not (every? #(= (% started) (% evidence)) joined-keys)
        (fail-evidence "started record does not match evidence"))
      (when-not (= (sha256 (Files/readAllBytes started-path)) (:startedSha256 evidence))
        (fail-evidence "started record digest does not match evidence")))))

(defn- verify-evidence! [control ^Path path]
  (let [record (read-canonical! path)
        _ (when-not (= contract/evidence-keys (set (keys record)))
            (fail-evidence "evidence shape is invalid"))
        evidence (verify-record! record (derived-key control evidence-key-domain)
                                 evidence-record-domain)
        run-id (:runId evidence)
        expected-run-id (str (.getFileName (.getParent path)))
        sequence (parse-uint64 (:commitSequence evidence) false)]
    (when-not (= "sentinel-evidence-v1" (:schemaVersion evidence))
      (fail-evidence "evidence version is invalid"))
    (when-not (and (valid-run-id? run-id) (= expected-run-id run-id))
      (fail-evidence "evidence run identity is invalid"))
    (contract/validate-body! evidence (:keyEpoch control) (project-state-hmac control) true)
    (validate-started! path evidence)
    {:sequence sequence :evidence record}))

(defn- duplicates [values]
  (->> values frequencies (keep (fn [[value count]] (when (> count 1) value))) seq))

(defn- validate-completed! [completed]
  (when (or (duplicates (map :sequence completed))
            (duplicates (map #(get-in % [:evidence :runId]) completed)))
    (fail-evidence "completed run identity is duplicated"))
  completed)

(defn- validate-high-water! [current completed]
  (when (and (seq completed) (nil? current))
    (fail-evidence "commit sequence is missing"))
  (let [floor (reduce max 0N (map :sequence completed))
        current-value (or current 0N)]
    (when (< current-value floor)
      (fail-evidence "commit sequence was rolled back"))
    current-value))

(defn- state-view! [^Path state-root control]
  (let [completed (->> (evidence-paths state-root)
                       (mapv #(verify-evidence! control %))
                       validate-completed!)
        current (read-sequence! state-root control)
        high-water (validate-high-water! current completed)]
    {:high-water high-water
     :completed (->> completed (sort-by :sequence) (mapv :evidence))}))

(defn- validate-layout! [^Path state-root]
  (when-not (directory? state-root)
    (fail-evidence "state root is invalid"))
  (when-not (directory? (.resolve state-root "runs"))
    (fail-evidence "run store is invalid"))
  (when-not (regular-file? (.resolve state-root "project.json"))
    (fail-evidence "project control is invalid"))
  (when-not (regular-file? (.resolve state-root "commit.lock"))
    (fail-evidence "commit lock is invalid"))
  state-root)

(defn- initialize-state! [^Path project-root]
  (let [sentinel-root (mkdir (.resolve project-root ".sentinel"))
        state-root (mkdir (.resolve sentinel-root "state-v1"))]
    (mkdir (.resolve state-root "runs"))
    (write-new (.resolve state-root "project.json")
               (contract-json/write-canonical-bytes (new-control)))
    (write-new (.resolve state-root "commit.lock") (byte-array 0))
    (force-directory state-root)
    state-root))

(defn- state-for-commit! [^Path project-root]
  (let [state-root (.resolve project-root ".sentinel/state-v1")]
    (if (exists? state-root)
      (validate-layout! state-root)
      (initialize-state! project-root))))

(defn- open-lock [^Path state-root]
  (FileChannel/open (.resolve state-root "commit.lock")
                    (into-array OpenOption [StandardOpenOption/READ StandardOpenOption/WRITE])))

(defn- started-record [run-id correlation-id command started-at]
  {:schemaVersion "sentinel-started-v1"
   :runId run-id :correlationId correlation-id :command command
   :language "clojure" :startedAtUtc started-at})

(defn- evidence-body [control run-id correlation-id command mode component-results
                      diagnostic-codes exit-code sequence started-at completed-at
                      committed-at started-sha]
  (let [status (contract/terminal-status exit-code)]
    {:schemaVersion "sentinel-evidence-v1"
     :specVersion "1.0.0"
     :fingerprintVersion "sentinel-fingerprint-v1"
     :runId run-id
     :correlationId correlation-id
     :command command
     :language "clojure"
     :mode mode
     :observationSource "fresh"
     :sourceRunId nil
     :certification (contract/certification mode status component-results)
     :commitSequence (str sequence)
     :keyEpoch (:keyEpoch control)
     :projectStateHmac (project-state-hmac control)
     :startedAtUtc started-at
     :completedAtUtc completed-at
     :committedAtUtc committed-at
     :startedSha256 started-sha
     :terminalStatus status
     :exitCode exit-code
     :components component-results
     :diagnosticCodes diagnostic-codes
     :eventCount 0
     :events []}))

(defn- diagnostic-codes [findings]
  (->> findings (map :defect-kind) distinct sort vec))

(defn commit! [^Path project-root command correlation-id mode component-results findings exit-code]
  (let [state-root (state-for-commit! project-root)]
    (with-open [channel (open-lock state-root)
                lock (.lock channel 0 1 false)]
      (let [control (read-control! state-root)
            current (:high-water (state-view! state-root control))
            run-id (str (UUID/randomUUID))
            started-at (contract/canonical-timestamp (Instant/now))
            run-root (.resolve (.resolve state-root "runs") run-id)
            started-bytes (contract-json/write-canonical-bytes
                           (started-record run-id correlation-id command started-at))]
        (mkdir run-root)
        (write-new (.resolve run-root "started.json") started-bytes)
        (force-directory run-root)
        (let [sequence (allocate-sequence! state-root control current)
              completed-at (contract/canonical-timestamp (Instant/now))
              committed-at (contract/canonical-timestamp (Instant/now))
              body (evidence-body control run-id correlation-id command mode component-results
                                  (diagnostic-codes findings) exit-code sequence started-at
                                  completed-at committed-at (sha256 started-bytes))
              _ (contract/validate-body! body (:keyEpoch control)
                                         (project-state-hmac control) false)
              record (sign-record (derived-key control evidence-key-domain)
                                  evidence-record-domain body)]
          (write-new (.resolve run-root "evidence.json")
                     (contract-json/write-canonical-bytes record))
          (force-directory run-root)
          record)))))

(defn read-completed! [^Path project-root]
  (let [state-root (.resolve project-root ".sentinel/state-v1")]
    (if-not (exists? state-root)
      []
      (do
        (validate-layout! state-root)
        (with-open [channel (open-lock state-root)
                    lock (.lock channel 0 1 true)]
          (:completed (state-view! state-root (read-control! state-root))))))))

(defn state-root [^Path project-root]
  (.resolve project-root ".sentinel/state-v1"))
