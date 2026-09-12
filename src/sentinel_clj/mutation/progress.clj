(ns sentinel-clj.mutation.progress
  (:require [sentinel-clj.json :as contract-json])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path StandardCopyOption StandardOpenOption]
           [java.security MessageDigest SecureRandom]
           [java.util Base64 HexFormat UUID]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def schema-version "sentinel-clojure-mutation-progress-v1")
(def authentication-domain "SENTINEL\u0000clojure-mutation-progress\u0000v1\u0000")
(def phases #{"started" "discovery" "baseline" "candidate" "completed"})
(def record-keys
  #{:schemaVersion :nonce :sequence :phase :completedCandidates
    :totalCandidates :hmacSha256})
(def max-safe-integer 9007199254740991)

(defn- fail! [message]
  (throw (ex-info message {:error :progressInvalid})))

(defn- valid-count? [value]
  (and (integer? value) (<= 0 value max-safe-integer)))

(defn- require-state! [sequence phase completed total]
  (when-not (and (integer? sequence) (<= 1 sequence max-safe-integer))
    (fail! "progress sequence is invalid"))
  (when-not (contains? phases phase)
    (fail! "progress phase is invalid"))
  (when-not (and (valid-count? completed) (valid-count? total) (<= completed total))
    (fail! "progress candidate counts are invalid")))

(defn- decode-key [key]
  (try
    (when-not (and (string? key) (re-matches #"[0-9a-f]{64}" key))
      (fail! "progress key is invalid"))
    (.parseHex (HexFormat/of) ^String key)
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception _ (fail! "progress key is invalid"))))

(defn- joined-bytes [body]
  (let [prefix (.getBytes authentication-domain StandardCharsets/UTF_8)
        content (contract-json/write-canonical-value-bytes body)
        joined (byte-array (+ (alength prefix) (alength content)))]
    (System/arraycopy prefix 0 joined 0 (alength prefix))
    (System/arraycopy content 0 joined (alength prefix) (alength content))
    joined))

(defn- record-hmac [key body]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (decode-key key) "HmacSHA256"))
    (.formatHex (HexFormat/of) (.doFinal mac (joined-bytes body)))))

(defn- random-key []
  (let [content (byte-array 32)]
    (.nextBytes (SecureRandom.) content)
    (.formatHex (HexFormat/of) content)))

(defn new-contract [^Path module-root]
  {:path (.resolve module-root (str ".sentinel-mutation-progress-" (UUID/randomUUID) ".json"))
   :nonce (str (UUID/randomUUID))
   :key (random-key)})

(defn environment-values [contract]
  (let [path-bytes (.getBytes (str (:path contract)) StandardCharsets/UTF_8)
        encoded-path (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) path-bytes)]
    {"SCLJ_PROGRESS" (str encoded-path ":" (:nonce contract) ":" (:key contract))}))

(defn environment-contract []
  (let [[encoded-path nonce key :as fields]
        (some-> (System/getProperty "sentinel.clj.progress") (.split ":" -1) vec)]
    (when-not (= 3 (count fields))
      (fail! "progress environment contract is invalid"))
    (let [path (String. (.decode (Base64/getUrlDecoder) ^String encoded-path)
                        StandardCharsets/UTF_8)]
      {:path (Path/of path (make-array String 0)) :nonce nonce :key key})))

(defn- progress-body [contract sequence phase completed total]
  {:schemaVersion schema-version
   :nonce (:nonce contract)
   :sequence sequence
   :phase phase
   :completedCandidates completed
   :totalCandidates total})

(defn- write-atomic! [^Path path record]
  (let [temporary (.resolveSibling path (str "." (.getFileName path) "." (UUID/randomUUID) ".tmp"))]
    (try
      (Files/write temporary (contract-json/write-canonical-bytes record)
                   (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
      (Files/move temporary path
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists temporary)))))

(defn write! [contract sequence phase completed total]
  (require-state! sequence phase completed total)
  (let [body (progress-body contract sequence phase completed total)]
    (write-atomic! (:path contract) (assoc body :hmacSha256 (record-hmac (:key contract) body)))))

(defn- require-record! [contract record]
  (when-not (= record-keys (set (keys record)))
    (fail! "progress record shape is invalid"))
  (require-state! (:sequence record) (:phase record)
                  (:completedCandidates record) (:totalCandidates record))
  (when-not (and (= schema-version (:schemaVersion record))
                 (= (:nonce contract) (:nonce record)))
    (fail! "progress record identity is invalid"))
  (let [actual (:hmacSha256 record)
        expected (record-hmac (:key contract) (dissoc record :hmacSha256))]
    (when-not (and (string? actual)
                   (MessageDigest/isEqual (.getBytes ^String actual StandardCharsets/UTF_8)
                                          (.getBytes ^String expected StandardCharsets/UTF_8)))
      (fail! "progress authentication failed")))
  record)

(defn read! [contract]
  (let [path ^Path (:path contract)]
    (when (Files/exists path (make-array LinkOption 0))
      (when-not (and (Files/isRegularFile path (make-array LinkOption 0))
                     (not (Files/isSymbolicLink path)))
        (fail! "progress path is invalid"))
      (require-record! contract (contract-json/read-canonical-path path)))))

(defn new-emitter []
  (let [contract (environment-contract)
        sequence (atom 0)]
    (fn [phase completed total]
      (write! contract (swap! sequence inc) phase completed total))))

(defn watchdog-status [limits started-at now last-progress-at]
  (cond
    (>= (- now started-at) (:absolute-timeout-ms limits)) :absolute-timeout
    (and (nil? last-progress-at)
         (>= (- now started-at) (:startup-timeout-ms limits))) :startup-timeout
    (and last-progress-at
         (>= (- now last-progress-at) (:idle-timeout-ms limits))) :idle-timeout
    :else :running))
