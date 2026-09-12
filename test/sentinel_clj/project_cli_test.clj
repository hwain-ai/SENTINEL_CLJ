(ns sentinel-clj.project-cli-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sentinel-clj.cli :as cli]
            [sentinel-clj.crap.analyzer :as analyzer]
            [sentinel-clj.json :as contract-json])
  (:import [java.nio.file Files LinkOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def backend-commit "e27dd5df63c4efdd66438587d1c5f49e73661b69")
(def correlation-id "00000000-0000-4000-8000-000000000001")

(defn- temporary-directory []
  (Files/createTempDirectory "sentinel-clj-cli-" (make-array FileAttribute 0)))

(defn- copy-tree [^Path source-root ^Path target-root]
  (with-open [paths (Files/walk source-root (make-array java.nio.file.FileVisitOption 0))]
    (doseq [source (iterator-seq (.iterator paths))]
      (let [target (.resolve target-root (.relativize source-root source))]
        (if (Files/isDirectory source (make-array LinkOption 0))
          (Files/createDirectories target (make-array FileAttribute 0))
          (Files/copy source target
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/COPY_ATTRIBUTES])))))))

(defn- with-install-root [^Path root operation]
  (let [property "sentinel.clj.install-root"
        previous (System/getProperty property)]
    (try
      (System/setProperty property (str root))
      (operation)
      (finally
        (if previous
          (System/setProperty property previous)
          (System/clearProperty property))))))

(defn- file [^Path root relative]
  (.resolve root relative))

(defn- write-bytes [path content]
  (Files/createDirectories (.getParent ^Path path) (make-array FileAttribute 0))
  (Files/write path (.getBytes ^String content "UTF-8") (make-array java.nio.file.OpenOption 0)))

(defn- write-json [path value]
  (write-bytes path (json/write-str value)))

(defn- project-config []
  {:specVersion "1.0.0"
   :modules [{:id "api"
              :language "clojure"
              :root "."
              :production ["src/**/*.clj"]
              :testCommand ["clojure" "-M:test"]
              :testRoots ["test"]
              :testPatterns ["*_test.clj"]
              :coverage {:format "sentinel-cloverage-form-v1"
                         :report "coverage.json"
                         :command ["clojure" "-M:coverage"]}
              :mutation {:format "sentinel-clj-mutate-report-v1"
                         :report "mutation.json"
                         :command ["clojure" "-M:sentinel-mutation"]}}]})

(defn- source-row [source]
  (first (analyzer/analyze-source "src/app/core.clj" source)))

(defn- coverage-file [relative source hits]
  (let [row (first (analyzer/analyze-source relative source))]
    {:moduleRelativePath relative
     :sourceDigest (:source-digest row)
     :units [{:startByte (get-in row [:source-range :start-byte])
              :endByte (get-in row [:source-range :end-byte])
              :hits hits}]}))

(defn- coverage-report [source hits]
  (let [row (source-row source)]
    {:schemaVersion "sentinel-cloverage-form-v1"
     :basis "form"
     :moduleRelativePath "src/app/core.clj"
     :sourceDigest (:source-digest row)
     :units [{:startByte (get-in row [:source-range :start-byte])
              :endByte (get-in row [:source-range :end-byte])
              :hits hits}]}))

(defn- mutation-report [source raw-status]
  {:schemaVersion "sentinel-clj-mutate-report-v1"
   :backend {:name "clj-mutate" :sourceCommit backend-commit}
   :operatorInventory ["arithmetic" "boolean" "comparison" "conditional" "constant" "equality"]
   :sourceInventory [{:moduleRelativePath "src/app/core.clj"
                      :sourceDigest (:source-digest (source-row source))
                      :candidateIds ["mutant-1"]}]
   :candidates [{:id "mutant-1" :operator "conditional"}]
   :outcomes [(cond-> {:id "mutant-1" :rawStatus raw-status}
                (= raw-status "assertionFailure")
                (assoc :controlPassed true :replayMatched true))]
   :unauthorizedExclusion 0})

(defn- prepare-project [source coverage mutation]
  (let [root (temporary-directory)]
    (write-json (file root "sentinel.config.json") (project-config))
    (write-bytes (file root "src/app/core.clj") source)
    (when coverage (write-json (file root "coverage.json") coverage))
    (when mutation (write-json (file root "mutation.json") mutation))
    root))

(defn- execute [root & arguments]
  (cli/execute (vec (concat arguments ["--project" (str root) "--format" "json"]))))

(defn- evidence-files [root]
  (let [runs (file root ".sentinel/state-v1/runs")]
    (if (Files/exists runs (make-array java.nio.file.LinkOption 0))
      (->> (file-seq (.toFile runs))
           (filter #(= "evidence.json" (.getName ^java.io.File %)))
           (sort-by #(.getPath ^java.io.File %))
           vec)
      [])))

(defn- state-snapshot [root]
  (let [state (file root ".sentinel/state-v1")]
    (if-not (Files/exists state (make-array LinkOption 0))
      []
      (with-open [paths (Files/walk state (make-array java.nio.file.FileVisitOption 0))]
        (->> (iterator-seq (.iterator paths))
             (sort-by str)
             (mapv (fn [path]
                     (let [attributes (Files/readAttributes
                                       ^Path path BasicFileAttributes
                                       (make-array LinkOption 0))]
                       [(str (.relativize state path))
                        (str (.fileKey attributes))
                        (.toMillis (.lastModifiedTime attributes))
                        (when (.isRegularFile attributes)
                          (vec (Files/readAllBytes ^Path path)))]))))))))

(defn- read-json [path]
  (json/read-str (slurp (.toFile ^Path path) :encoding "UTF-8") :key-fn keyword))

(defn- overwrite [path content]
  (Files/write ^Path path ^bytes content
               (into-array java.nio.file.OpenOption
                           [StandardOpenOption/TRUNCATE_EXISTING
                            StandardOpenOption/WRITE])))

(defn- test-hmac [key content]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac ^bytes content)))

(defn- test-hex [content]
  (apply str (map #(format "%02x" (bit-and 0xff %)) content)))

(defn- test-sha256 [content]
  (test-hex (.digest (MessageDigest/getInstance "SHA-256") ^bytes content)))

(defn- join-bytes [left right]
  (let [joined (byte-array (+ (alength ^bytes left) (alength ^bytes right)))]
    (System/arraycopy left 0 joined 0 (alength ^bytes left))
    (System/arraycopy right 0 joined (alength ^bytes left) (alength ^bytes right))
    joined))

(defn- decode-control-key [value]
  (try
    (.decode (Base64/getUrlDecoder) ^String value)
    (catch IllegalArgumentException _
      (.decode (Base64/getDecoder) ^String value))))

(defn- resign-evidence! [path control update-body]
  (let [record (read-json path)
        body (update-body (dissoc record :hmacSha256))
        cleanup-key (decode-control-key (:cleanupLeaseKey control))
        derived-key (test-hmac cleanup-key
                               (.getBytes "SENTINEL\u0000evidence-key\u0000v1\u0000"
                                          "UTF-8"))
        mac-input (join-bytes
                   (.getBytes "SENTINEL\u0000evidence\u0000v1\u0000" "UTF-8")
                   (contract-json/write-canonical-value-bytes body))
        signed (assoc body :hmacSha256 (test-hex (test-hmac derived-key mac-input)))]
    (overwrite path (contract-json/write-canonical-bytes signed))))

(def evidence-body-keys
  #{:certification :command :commitSequence :committedAtUtc :completedAtUtc
    :components :correlationId :diagnosticCodes :eventCount :events :exitCode
    :fingerprintVersion :keyEpoch :language :mode :observationSource
    :projectStateHmac :runId :schemaVersion :sourceRunId :specVersion
    :startedAtUtc :startedSha256 :terminalStatus})

(def canonical-utc-pattern
  #"[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{0,8}[1-9])?Z")

(deftest doctor-validates-project-without-writing-state
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source nil nil)
        result (execute root "doctor")]
    (is (= 0 (:exit-code result)))
    (is (= "sentinel-doctor-v1" (get-in result [:body :schemaVersion])))
    (is (= 1 (get-in result [:body :productionFiles])))
    (is (= backend-commit (get-in result [:body :backend :sourceCommit])))
    (is (false? (Files/exists (file root ".sentinel") (make-array java.nio.file.LinkOption 0))))))

(deftest doctor-fails-closed-when-vendored-backend-is-changed
  (let [repository-root (.toAbsolutePath (Path/of (System/getProperty "user.dir")
                                                   (make-array String 0)))
        install-root (temporary-directory)
        project-root (prepare-project "(ns app.core)\n(defn answer [] 42)\n" nil nil)
        scripts (file install-root "scripts")
        vendored (file install-root "third_party/clj-mutate")]
    (Files/createDirectories scripts (make-array FileAttribute 0))
    (Files/copy (file repository-root "backend.lock.json")
                (file install-root "backend.lock.json")
                (make-array java.nio.file.CopyOption 0))
    (Files/copy (file repository-root "scripts/backend_lock.py")
                (file scripts "backend_lock.py")
                (make-array java.nio.file.CopyOption 0))
    (copy-tree (file repository-root "third_party/clj-mutate") vendored)
    (Files/write (file vendored "src/clj_mutate/mutations.cljc")
                 (.getBytes "\n" "UTF-8")
                 (into-array java.nio.file.OpenOption [StandardOpenOption/APPEND]))
    (let [result (with-install-root install-root #(execute project-root "doctor"))]
      (is (= 5 (:exit-code result)))
      (is (= "dependencyError" (get-in result [:body :code])))
      (is (false? (Files/exists (file project-root ".sentinel")
                                (make-array LinkOption 0)))))))

(deftest local-crap-commits-one-privacy-safe-immutable-evidence-bundle
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        result (execute root "crap" "--local" "--correlation-id" correlation-id)
        evidence-file (first (evidence-files root))
        evidence-path (.toPath evidence-file)
        evidence-bytes (Files/readAllBytes evidence-path)
        evidence (read-json evidence-path)
        body (dissoc evidence :hmacSha256)
        state-root (file root ".sentinel/state-v1")
        control (read-json (file state-root "project.json"))
        cleanup-key (decode-control-key (:cleanupLeaseKey control))
        project-state-input
        (join-bytes (.getBytes "SENTINEL\u0000project-state-binding\u0000v1\u0000" "UTF-8")
                    (decode-control-key (:projectIdentifier control)))
        evidence-key (test-hmac cleanup-key
                                (.getBytes "SENTINEL\u0000evidence-key\u0000v1\u0000"
                                           "UTF-8"))
        evidence-input
        (join-bytes (.getBytes "SENTINEL\u0000evidence\u0000v1\u0000" "UTF-8")
                    (contract-json/write-canonical-value-bytes body))
        times (mapv #(Instant/parse (% evidence))
                    [:startedAtUtc :completedAtUtc :committedAtUtc])]
    (is (= 0 (:exit-code result)))
    (is (= "passed" (get-in result [:body :run :terminalStatus])))
    (is (= "1" (get-in result [:body :crap :maxNumerator])))
    (is (= "1" (get-in result [:body :crap :maxDenominator])))
    (is (= 1 (count (evidence-files root))))
    (is (= (conj evidence-body-keys :hmacSha256) (set (keys evidence))))
    (is (= #{:callableCount :maxNumerator :maxDenominator :pass :unknownCount}
           (set (keys (get-in evidence [:components :crap])))))
    (is (= "sentinel-fingerprint-v1" (:fingerprintVersion evidence)))
    (is (= "fresh" (:observationSource evidence)))
    (is (nil? (:sourceRunId evidence)))
    (is (= 1 (:keyEpoch evidence)))
    (is (false? (:certification evidence)))
    (is (= [] (:diagnosticCodes evidence)))
    (is (= 0 (:eventCount evidence)))
    (is (= [] (:events evidence)))
    (is (= (test-sha256 (Files/readAllBytes (file (.getParent evidence-path) "started.json")))
           (:startedSha256 evidence)))
    (is (= (test-hex (test-hmac cleanup-key project-state-input))
           (:projectStateHmac evidence)))
    (is (= (test-hex (test-hmac evidence-key evidence-input)) (:hmacSha256 evidence)))
    (is (every? #(re-matches canonical-utc-pattern (% evidence))
                [:startedAtUtc :completedAtUtc :committedAtUtc]))
    (is (apply <= (map #(.toEpochMilli ^Instant %) times)))
    (is (not (.contains (String. evidence-bytes "UTF-8") (str root))))
    (is (not (.contains (String. evidence-bytes "UTF-8") "app.core")))
    (is (java.util.Arrays/equals evidence-bytes (Files/readAllBytes evidence-path)))))

(deftest all-unknown-crap-uses-the-canonical-zero-over-one-sentinel
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        report (assoc (coverage-report source 1) :units [])
        root (prepare-project source report nil)
        result (execute root "crap" "--local")
        evidence (read-json (.toPath ^java.io.File (first (evidence-files root))))]
    (is (= 2 (:exit-code result)))
    (is (= {:callableCount 1
            :maxNumerator "0"
            :maxDenominator "1"
            :pass false
            :unknownCount 1}
           (get-in evidence [:components :crap])))
    (is (= "qualityFailed" (:terminalStatus evidence)))
    (is (false? (:certification evidence)))))

(deftest local-crap-joins-every-production-file-from-one-report
  (let [first-source "(ns app.first)\n(defn first-answer [] 1)\n"
        last-source "(ns app.last)\n(defn last-answer [] 2)\n"
        root (temporary-directory)
        report {:schemaVersion "sentinel-cloverage-form-v1"
                :basis "form"
                :files [(coverage-file "src/app/first.clj" first-source 1)
                        (coverage-file "src/app/last.clj" last-source 1)]}]
    (write-json (file root "sentinel.config.json") (project-config))
    (write-bytes (file root "src/app/first.clj") first-source)
    (write-bytes (file root "src/app/last.clj") last-source)
    (write-json (file root "coverage.json") report)
    (let [result (execute root "crap" "--local")]
      (is (= 0 (:exit-code result)))
      (is (true? (get-in result [:body :crap :pass])))
      (is (= 2 (get-in result [:body :crap :callableCount])))
      (is (= 0 (get-in result [:body :crap :unknownCount])))
      (is (= 1 (count (evidence-files root)))))
    (write-json (file root "coverage.json") (assoc report :files [(first (:files report))]))
    (let [missing (execute root "crap" "--local")]
      (is (= 2 (:exit-code missing)))
      (is (= 1 (get-in missing [:body :crap :unknownCount]))))
    (write-json (file root "coverage.json")
                (assoc report :files (conj (:files report) (first (:files report)))))
    (let [duplicate (execute root "crap" "--local")]
      (is (= 5 (:exit-code duplicate)))
      (is (= "coverageReportInvalid" (get-in duplicate [:body :diagnostics 0]))))
    (write-json (file root "coverage.json")
                (assoc report :files (conj (:files report)
                                           (coverage-file "src/app/extra.clj"
                                                          "(ns app.extra)\n(defn extra [] 3)\n"
                                                          1))))
    (let [outside (execute root "crap" "--local")]
      (is (= 5 (:exit-code outside)))
      (is (= "coverageReportInvalid" (get-in outside [:body :diagnostics 0]))))))

(deftest local-mutation-preserves-nine-state-gate-and-commits-evidence
  (let [source "(ns app.core)\n(defn answer [] (if true 42 0))\n"
        passing-root (prepare-project source nil (mutation-report source "assertionFailure"))
        timeout-root (prepare-project source nil (mutation-report source "timeout"))
        passing (execute passing-root "mutation" "--local")
        timeout (execute timeout-root "mutation" "--local")]
    (is (= 0 (:exit-code passing)))
    (is (true? (get-in passing [:body :mutation :pass])))
    (is (= 1 (get-in passing [:body :mutation :killed])))
    (is (= 2 (:exit-code timeout)))
    (is (false? (get-in timeout [:body :mutation :pass])))
    (is (= 1 (get-in timeout [:body :mutation :timedOut])))
    (is (= 1 (count (evidence-files timeout-root))))
    (is (= #{:inScope :killed :survived :uncovered :timedOut :compileError
             :runtimeError :pending :ignored :toolError :unauthorizedExclusion :pass}
           (-> timeout-root evidence-files first .toPath read-json
               (get-in [:components :mutation]) keys set)))))

(deftest backend-failures-use-exit-six-and-still-commit-terminal-evidence
  (testing "explicit mutant tool error"
    (let [source "(ns app.core)\n(defn answer [] (if true 42 0))\n"
          root (prepare-project source nil (mutation-report source "toolError"))
          result (execute root "mutation" "--local")]
      (is (= 6 (:exit-code result)))
      (is (= "backendError" (get-in result [:body :run :terminalStatus])))
      (is (= 1 (count (evidence-files root))))))
  (testing "unknown backend state"
    (let [source "(ns app.core)\n(defn answer [] (if true 42 0))\n"
          root (prepare-project source nil (mutation-report source "magic"))
          result (execute root "mutation" "--local")]
      (is (= 6 (:exit-code result)))
      (is (= "unknownRawState" (get-in result [:body :diagnostics 0])))
      (is (= 1 (count (evidence-files root)))))))

(deftest unresolved-finding-events-are-not-invented-or-written
  (let [source "(ns app.core)\n(defn answer [] (if true 42 0))\n"
        root (prepare-project source nil (mutation-report source "timeout"))
        first-result (execute root "mutation" "--local" "--correlation-id" correlation-id)
        first-file (first (evidence-files root))
        first-bytes (Files/readAllBytes (.toPath first-file))
        second (execute root "mutation" "--local" "--correlation-id" correlation-id)
        history (execute root "history" "--repeated")]
    (is (= 2 (:exit-code first-result)))
    (is (= 2 (:exit-code second)))
    (is (= 0 (:exit-code history)))
    (is (= 2 (get-in history [:body :completedRuns])))
    (is (= [] (get-in history [:body :findings])))
    (is (every? #(and (zero? (:eventCount %)) (= [] (:events %)))
                (map #(read-json (.toPath ^java.io.File %)) (evidence-files root))))
    (is (java.util.Arrays/equals first-bytes (Files/readAllBytes (.toPath first-file))))))

(deftest state-control-uses-distinct-owner-secrets
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        control (read-json (file root ".sentinel/state-v1/project.json"))
        fingerprint-key (.decode (Base64/getUrlDecoder) ^String (:fingerprintHmacKey control))
        cleanup-key (.decode (Base64/getUrlDecoder) ^String (:cleanupLeaseKey control))]
    (is (= #{:schemaVersion :stateVersion :projectIdentifier :fingerprintHmacKey
             :cleanupLeaseKey :keyEpoch}
           (set (keys control))))
    (is (= "sentinel-project-state-v1" (:schemaVersion control)))
    (is (= "state-v1" (:stateVersion control)))
    (is (= 1 (:keyEpoch control)))
    (is (= 16 (count (.decode (Base64/getUrlDecoder) ^String (:projectIdentifier control)))))
    (is (every? #(not (.contains ^String % "="))
                [(:projectIdentifier control) (:fingerprintHmacKey control)
                 (:cleanupLeaseKey control)]))
    (is (= 32 (count fingerprint-key)))
    (is (= 32 (count cleanup-key)))
    (is (not (java.util.Arrays/equals fingerprint-key cleanup-key)))))

(deftest history-rejects-legacy-project-state-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        path (file root ".sentinel/state-v1/project.json")
        control (read-json path)
        legacy {:schemaVersion "sentinel-project-control-v1"
                :projectIdentifier (:projectIdentifier control)
                :fingerprintKey (or (:fingerprintHmacKey control) (:fingerprintKey control))
                :cleanupLeaseKey (:cleanupLeaseKey control)
                :keyEpoch (:keyEpoch control)}]
    (overwrite path (contract-json/write-canonical-bytes legacy))
    (let [before (state-snapshot root)
          result (execute root "history")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest history-rejects-legacy-authentication-fields-without-writing
  (testing "commit sequence legacy hmac field"
    (let [source "(ns app.core)\n(defn answer [] 42)\n"
          root (prepare-project source (coverage-report source 1) nil)
          _ (execute root "crap" "--local")
          path (file root ".sentinel/state-v1/commit-sequence.json")
          record (read-json path)
          legacy (-> record (dissoc :hmacSha256) (assoc :hmac (:hmacSha256 record)))]
      (overwrite path (contract-json/write-canonical-bytes legacy))
      (let [before (state-snapshot root)
            result (execute root "history")]
        (is (= 7 (:exit-code result)))
        (is (= "evidenceError" (get-in result [:body :code])))
        (is (= before (state-snapshot root))))))
  (testing "evidence legacy hmac field"
    (let [source "(ns app.core)\n(defn answer [] 42)\n"
          root (prepare-project source (coverage-report source 1) nil)
          _ (execute root "crap" "--local")
          path (.toPath ^java.io.File (first (evidence-files root)))
          record (read-json path)
          legacy (-> record (dissoc :hmacSha256) (assoc :hmac (:hmacSha256 record)))]
      (overwrite path (contract-json/write-canonical-bytes legacy))
      (let [before (state-snapshot root)
            result (execute root "history")]
        (is (= 7 (:exit-code result)))
        (is (= "evidenceError" (get-in result [:body :code])))
        (is (= before (state-snapshot root)))))))

(deftest history-rejects-noncanonical-base64url-state-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        path (file root ".sentinel/state-v1/project.json")
        control (read-json path)
        identifier (:projectIdentifier control)
        replacement ({\A \B, \Q \R, \g \h, \w \x} (last identifier))
        noncanonical (str (subs identifier 0 (dec (count identifier))) replacement)]
    (is (java.util.Arrays/equals (.decode (Base64/getUrlDecoder) ^String identifier)
                                (.decode (Base64/getUrlDecoder) ^String noncanonical)))
    (overwrite path (contract-json/write-canonical-bytes
                     (assoc control :projectIdentifier noncanonical)))
    (let [before (state-snapshot root)
          result (execute root "history")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest terminal-files-use-canonical-authenticated-sequences
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        sequence-path (file root ".sentinel/state-v1/commit-sequence.json")
        evidence-path (.toPath ^java.io.File (first (evidence-files root)))
        control (read-json (file root ".sentinel/state-v1/project.json"))
        sequence (read-json sequence-path)
        evidence (read-json evidence-path)
        cleanup-key (decode-control-key (:cleanupLeaseKey control))
        sequence-key (test-hmac cleanup-key
                                (.getBytes "SENTINEL\u0000commit-sequence-key\u0000v1\u0000"
                                           "UTF-8"))
        mac-input (.getBytes
                   (str "SENTINEL\u0000commit-sequence\u0000v1\u0000"
                        "{\"lastAllocated\":\"1\",\"version\":\"commit-sequence-v1\"}")
                   "UTF-8")]
    (is (= #{:version :lastAllocated :hmacSha256} (set (keys sequence))))
    (is (= "commit-sequence-v1" (:version sequence)))
    (is (= "1" (:lastAllocated sequence)))
    (is (re-matches #"[0-9a-f]{64}" (:hmacSha256 sequence)))
    (is (= (test-hex (test-hmac sequence-key mac-input)) (:hmacSha256 sequence)))
    (is (= "1" (:commitSequence evidence)))
    (is (re-matches #"[0-9a-f]{64}" (:hmacSha256 evidence)))
    (is (.endsWith (String. (Files/readAllBytes sequence-path) "UTF-8") "\n"))
    (is (.endsWith (String. (Files/readAllBytes evidence-path) "UTF-8") "\n"))))

(deftest history-rejects-zero-commit-sequence-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        state-root (file root ".sentinel/state-v1")
        control (read-json (file state-root "project.json"))
        cleanup-key (decode-control-key (:cleanupLeaseKey control))
        sequence-key (test-hmac cleanup-key
                                (.getBytes "SENTINEL\u0000commit-sequence-key\u0000v1\u0000"
                                           "UTF-8"))
        body {:lastAllocated "0" :version "commit-sequence-v1"}
        mac-input (.getBytes
                   (str "SENTINEL\u0000commit-sequence\u0000v1\u0000"
                        "{\"lastAllocated\":\"0\",\"version\":\"commit-sequence-v1\"}")
                   "UTF-8")
        sequence (assoc body :hmacSha256 (test-hex (test-hmac sequence-key mac-input)))
        evidence-path (.toPath ^java.io.File (first (evidence-files root)))
        run-root (.getParent evidence-path)]
    (Files/delete evidence-path)
    (Files/delete (.resolve run-root "started.json"))
    (Files/delete run-root)
    (overwrite (file state-root "commit-sequence.json")
               (contract-json/write-canonical-bytes sequence))
    (let [before (state-snapshot root)
          result (execute root "history")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest history-rejects-authenticated-sequence-rollback-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        sequence-path (file root ".sentinel/state-v1/commit-sequence.json")]
    (execute root "crap" "--local")
    (let [first-sequence (Files/readAllBytes sequence-path)]
      (execute root "crap" "--local")
      (overwrite sequence-path first-sequence))
    (let [before (state-snapshot root)
          result (execute root "history")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest commit-rejects-authenticated-sequence-rollback-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        sequence-path (file root ".sentinel/state-v1/commit-sequence.json")]
    (execute root "crap" "--local")
    (let [first-sequence (Files/readAllBytes sequence-path)]
      (execute root "crap" "--local")
      (overwrite sequence-path first-sequence))
    (let [before (state-snapshot root)
          result (execute root "crap" "--local")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest history-rejects-evidence-tamper-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        evidence-path (.toPath ^java.io.File (first (evidence-files root)))
        original (String. (Files/readAllBytes evidence-path) "UTF-8")
        changed (.replace original "\"exitCode\":0" "\"exitCode\":2")]
    (is (not= original changed))
    (overwrite evidence-path (.getBytes changed "UTF-8"))
    (let [before (state-snapshot root)
          result (execute root "history")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest history-rejects-authenticated-evidence-semantic-tamper-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        reject! (fn [command coverage mutation mutate]
                  (let [root (prepare-project source coverage mutation)
                        _ (execute root command "--local")
                        evidence-path (.toPath ^java.io.File (first (evidence-files root)))
                        control (read-json (file root ".sentinel/state-v1/project.json"))]
                    (resign-evidence! evidence-path control mutate)
                    (let [before (state-snapshot root)
                          result (execute root "history")]
                      (is (= 7 (:exit-code result)))
                      (is (= "evidenceError" (get-in result [:body :code])))
                      (is (= before (state-snapshot root))))))]
    (doseq [[label mutate]
            [["noncanonical UTC"
              #(assoc % :completedAtUtc "2026-01-01T00:00:00.120Z")]
             ["year zero UTC"
              #(assoc % :completedAtUtc "0000-01-01T00:00:00Z")]
             ["reversed time order"
              #(assoc % :completedAtUtc "2000-01-01T00:00:00Z")]
             ["fresh source run"
              #(assoc % :sourceRunId "00000000-0000-4000-8000-000000000002")]
             ["false local certification"
              #(assoc % :certification true)]
             ["noncontiguous event ordinal"
              #(assoc % :eventCount 1
                        :events [{:filename (str (apply str (repeat 31 "0")) "2.json")
                                  :sha256 (apply str (repeat 64 "0"))}])]
             ["component/terminal disagreement"
              #(assoc % :terminalStatus "qualityFailed" :exitCode 2)]
             ["CRAP all-unknown mismatch"
              #(assoc-in % [:components :crap :unknownCount] 1)]
             ["unsorted diagnostics"
              #(assoc % :diagnosticCodes ["zCode" "aCode"])]
             ["wrong project binding"
              #(assoc % :projectStateHmac (apply str (repeat 64 "0")))]]]
      (testing label
        (reject! "crap" (coverage-report source 1) nil mutate)))
    (doseq [[label mutate]
            [["mutation count sum"
              #(assoc-in % [:components :mutation :inScope] 2)]
             ["mutation pass semantics"
              #(assoc-in % [:components :mutation :pass] false)]
             ["mutation tool error precedence"
              #(-> %
                   (assoc-in [:components :mutation :killed] 0)
                   (assoc-in [:components :mutation :toolError] 1)
                   (assoc-in [:components :mutation :pass] false)
                   (assoc :terminalStatus "qualityFailed" :exitCode 2))]]]
      (testing label
        (reject! "mutation" nil (mutation-report source "assertionFailure") mutate)))))

(deftest evidence-project-binding-survives-fingerprint-key-rotation-and-enforces-epochs
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)
        _ (execute root "crap" "--local")
        project-path (file root ".sentinel/state-v1/project.json")
        old-control (read-json project-path)
        old-evidence (read-json (.toPath ^java.io.File (first (evidence-files root))))
        replacement-key (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                         (byte-array (repeat 32 (byte 0))))
        rotated-control (assoc old-control :fingerprintHmacKey replacement-key :keyEpoch 2)]
    (overwrite project-path (contract-json/write-canonical-bytes rotated-control))
    (is (= 0 (:exit-code (execute root "history"))))
    (is (= 0 (:exit-code (execute root "crap" "--local"))))
    (let [evidence (->> (evidence-files root)
                        (map #(.toPath ^java.io.File %))
                        (map read-json)
                        (sort-by #(bigint (:commitSequence %)))
                        vec)]
      (is (= [1 2] (mapv :keyEpoch evidence)))
      (is (= (mapv :projectStateHmac evidence)
             (repeat 2 (:projectStateHmac old-evidence))))
      (overwrite project-path (contract-json/write-canonical-bytes old-control))
      (let [before (state-snapshot root)
            result (execute root "history")]
        (is (= 7 (:exit-code result)))
        (is (= "evidenceError" (get-in result [:body :code])))
        (is (= before (state-snapshot root)))))))

(deftest history-rejects-duplicate-completion-sequence-without-writing
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1) nil)]
    (execute root "crap" "--local")
    (execute root "crap" "--local")
    (let [[first-evidence second-evidence] (evidence-files root)]
      (overwrite (.toPath ^java.io.File second-evidence)
                 (Files/readAllBytes (.toPath ^java.io.File first-evidence))))
    (let [before (state-snapshot root)
          result (execute root "history")]
      (is (= 7 (:exit-code result)))
      (is (= "evidenceError" (get-in result [:body :code])))
      (is (= before (state-snapshot root))))))

(deftest check-collects-crap-and-mutation-under-one-run
  (let [source "(ns app.core)\n(defn answer [] 42)\n"
        root (prepare-project source (coverage-report source 1)
                              (mutation-report source "assertionFailure"))
        result (execute root "check" "--local")]
    (is (= 0 (:exit-code result)))
    (is (true? (get-in result [:body :crap :pass])))
    (is (true? (get-in result [:body :mutation :pass])))
    (is (= 1 (count (evidence-files root))))))

(deftest strict-mutation-runs-command-in-disposable-snapshot
  (let [source "(ns app.core)\n(defn answer [] (if true 42 0))\n"
        root (prepare-project source nil nil)
        config (assoc-in (project-config) [:modules 0 :mutation :command]
                         ["/usr/bin/cp" "planned-mutation.json" "mutation.json"])
        original-source (Files/readAllBytes (file root "src/app/core.clj"))]
    (write-json (file root "sentinel.config.json") config)
    (write-json (file root "planned-mutation.json") (mutation-report source "assertionFailure"))
    (let [result (execute root "mutation" "--strict")]
      (is (= 0 (:exit-code result)))
      (is (true? (get-in result [:body :mutation :pass])))
      (is (true? (:certification
                  (read-json (.toPath ^java.io.File (first (evidence-files root)))))))
      (is (false? (Files/exists (file root "mutation.json")
                                (make-array java.nio.file.LinkOption 0))))
      (is (java.util.Arrays/equals original-source
                                  (Files/readAllBytes (file root "src/app/core.clj")))))))

(deftest strict-mutation-executes-the-vendored-clj-mutate-companion
  (let [repository-root (.toAbsolutePath (Path/of (System/getProperty "user.dir")
                                                   (make-array String 0)))
        bridge-script (str (.resolve repository-root "scripts/clj-mutate-bridge.sh"))
        runner-script (str (.resolve repository-root "scripts/clojure-test-events.sh"))
        source "(ns app.core)\n(defn answer [] (inc 41))\n"
        zero-candidate-source "(ns app.empty)\n(defn identity-value [value] value)\n"
        root (prepare-project source nil nil)
        config (-> (project-config)
                   (assoc-in [:modules 0 :mutation :command]
                             [bridge-script "--project" "." "--config"
                              "sentinel.config.json" "--module" "api"])
                   (assoc-in [:modules 0 :testCommand]
                             [runner-script "app.core-test"]))
        test-source (str "(ns app.core-test\n"
                         "  (:require [clojure.test :refer [deftest is]]\n"
                         "            [app.core :as core]))\n"
                         "(deftest answer-test (is (= 42 (core/answer))))\n")]
    (write-json (file root "sentinel.config.json") config)
    (write-bytes (file root "src/app/empty.clj") zero-candidate-source)
    (write-bytes (file root "test/app/core_test.clj") test-source)
    (let [result (execute root "mutation" "--strict")]
      (is (= 0 (:exit-code result)))
      (is (= 1 (get-in result [:body :mutation :inScope])))
      (is (= 1 (get-in result [:body :mutation :killed])))
      (is (false? (Files/exists (file root "mutation.json")
                                (make-array java.nio.file.LinkOption 0)))))))

(deftest strict-mutation-preserves-baseline-failed-exit-four
  (let [repository-root (.toAbsolutePath (Path/of (System/getProperty "user.dir")
                                                   (make-array String 0)))
        bridge-script (str (.resolve repository-root "scripts/clj-mutate-bridge.sh"))
        runner-script (str (.resolve repository-root "scripts/clojure-test-events.sh"))
        source "(ns app.core)\n(defn answer [] (inc 41))\n"
        root (prepare-project source nil nil)
        config (-> (project-config)
                   (assoc-in [:modules 0 :mutation :command]
                             [bridge-script "--project" "." "--config"
                              "sentinel.config.json" "--module" "api"])
                   (assoc-in [:modules 0 :testCommand]
                             [runner-script "app.core-test"]))
        failing-test "(ns app.core-test (:require [clojure.test :refer [deftest is]] [app.core :as core]))\n(deftest answer-test (is (= 43 (core/answer))))\n"]
    (write-json (file root "sentinel.config.json") config)
    (write-bytes (file root "test/app/core_test.clj") failing-test)
    (let [result (execute root "mutation" "--strict")]
      (is (= 4 (:exit-code result)))
      (is (= "baselineFailed" (get-in result [:body :run :terminalStatus])))
      (is (= "baselineFailed" (get-in result [:body :diagnostics 0])))
      (is (false? (:certification
                   (read-json (.toPath ^java.io.File (first (evidence-files root)))))))
      (is (= 1 (count (evidence-files root))))
      (is (false? (Files/exists (file root "mutation.json")
                                (make-array java.nio.file.LinkOption 0)))))))

(deftest repository-launcher-is-harness-neutral-and-help-is-read-only
  (let [repository-root (.toAbsolutePath (Path/of (System/getProperty "user.dir")
                                                   (make-array String 0)))
        launcher (str (.resolve repository-root "scripts/sentinel-clj.sh"))
        process (.start (ProcessBuilder. ^java.util.List [launcher "--help"]))
        output (slurp (.getInputStream process) :encoding "UTF-8")]
    (is (= 0 (.waitFor process)))
    (is (= "sentinel-help-v1" (:schemaVersion (json/read-str output :key-fn keyword))))
    (is (false? (Files/exists (.resolve repository-root ".sentinel")
                              (make-array java.nio.file.LinkOption 0))))))

(deftest invalid-config-and-empty-history-never-create-state
  (testing "invalid config"
    (let [root (temporary-directory)]
      (write-json (file root "sentinel.config.json") (assoc (project-config) :unexpected true))
      (is (= {:exit-code 3
              :body {:schemaVersion "sentinel-error-v1"
                     :code "projectConfigShapeInvalid"}}
             (execute root "doctor")))
      (is (false? (Files/exists (file root ".sentinel") (make-array java.nio.file.LinkOption 0))))))
  (testing "invalid correlation ID"
    (let [source "(ns app.core)\n(defn answer [] 42)\n"
          root (prepare-project source (coverage-report source 1) nil)
          result (execute root "crap" "--local" "--correlation-id" "not-a-uuid")]
      (is (= 3 (:exit-code result)))
      (is (= "argumentInvalid" (get-in result [:body :code])))
      (is (false? (Files/exists (file root ".sentinel")
                                (make-array java.nio.file.LinkOption 0))))))
  (testing "empty history"
    (let [root (temporary-directory)
          result (execute root "history")]
      (is (= 0 (:exit-code result)))
      (is (= 0 (get-in result [:body :completedRuns])))
      (is (false? (Files/exists (file root ".sentinel") (make-array java.nio.file.LinkOption 0)))))))
