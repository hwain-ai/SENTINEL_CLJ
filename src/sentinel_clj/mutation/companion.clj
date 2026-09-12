(ns sentinel-clj.mutation.companion
  (:require [clj-mutate.source :as backend-source]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [sentinel-clj.config :as config]
            [sentinel-clj.crap.analyzer :as analyzer]
            [sentinel-clj.crap.models :as models]
            [sentinel-clj.json :as contract-json]
            [sentinel-clj.mutation.clj-mutate-bridge :as bridge]
            [sentinel-clj.mutation.progress :as progress]
            [sentinel-clj.mutation.typed-replay :as typed-replay])
  (:import [java.nio.file Files LinkOption OpenOption Path StandardOpenOption]
           [java.security SecureRandom]
           [java.util HexFormat UUID]
           [java.util.concurrent TimeUnit]))

(def ^:private signature-random (SecureRandom.))

(defn- new-signature-key []
  (let [content (byte-array 32)]
    (.nextBytes signature-random content)
    (.formatHex (HexFormat/of) content)))

(defn- parse-arguments [arguments]
  (when (or (odd? (count arguments))
            (some #(not (#{"--project" "--config" "--module"} %)) (take-nth 2 arguments)))
    (throw (ex-info "invalid companion arguments" {:error :argumentInvalid})))
  (let [options (into {} (map (fn [[key value]] [(keyword (subs key 2)) value])
                              (partition 2 arguments)))]
    (when-not (= #{:project :config :module} (set (keys options)))
      (throw (ex-info "companion arguments are incomplete" {:error :argumentInvalid})))
    options))

(defn- module-relative [project ^Path path]
  (str (.relativize ^Path (:module-root project) path)))

(defn- candidate-id [path site]
  (str path "::" (:form-index site) "::" (:index site)))

(defn- discover-file [project ^Path path]
  (let [relative (module-relative project path)
        content-bytes (Files/readAllBytes path)
        content (String. content-bytes "UTF-8")]
    (analyzer/analyze-bytes relative content-bytes)
    (let [forms (binding [reader/*read-eval* false]
                  (backend-source/read-source-forms content))]
      (let [candidates (mapv (fn [site]
                               {:id (candidate-id relative site)
                                :operator (name (:category site))
                                :path path
                                :content content
                                :site site})
                             (backend-source/discover-all-mutations forms))]
        {:source {:moduleRelativePath relative
                  :sourceDigest (models/sha256-bytes content-bytes)
                  :candidateIds (mapv :id candidates)}
         :candidates candidates}))))

(defn- discover [project]
  (let [files (mapv #(discover-file project %) (:production-files project))]
    {:source-inventory (mapv :source files)
     :candidates (vec (mapcat :candidates files))}))

(defn- event-path [project]
  (.resolve ^Path (:module-root project) (str ".sentinel-test-event-" (UUID/randomUUID) ".json")))

(defn- test-selector-arguments [selected-test-ids]
  (mapcat (fn [identifier] ["--sentinel-test-id" identifier]) selected-test-ids))

(defn- process-builder [project nonce signature-key event-path selected-test-ids]
  (let [configured (get-in project [:module :testCommand])
        command (vec (concat [(first configured) "--sentinel-nonce" nonce
                              "--sentinel-signature-key" signature-key
                              "--sentinel-report" (str event-path)]
                            (test-selector-arguments selected-test-ids)
                            (rest configured)))
        builder (ProcessBuilder. ^java.util.List command)
        environment (.environment builder)]
    (.clear environment)
    (.put environment "PATH" "/usr/bin:/bin")
    (.put environment "LANG" "C.UTF-8")
    (.put environment "LC_ALL" "C.UTF-8")
    (.directory builder (.toFile ^Path (:module-root project)))
    (.redirectOutput builder java.lang.ProcessBuilder$Redirect/DISCARD)
    (.redirectError builder java.lang.ProcessBuilder$Redirect/DISCARD)
    builder))

(defn- classify-event [report process-exit]
  (cond
    (pos? (get-in report [:summary :error])) "runtimeError"
    (pos? (get-in report [:summary :fail])) "assertionFailure"
    (and (zero? process-exit)
         (zero? (get-in report [:summary :error]))
         (zero? (get-in report [:summary :fail]))) "passed"
    :else "toolError"))

(defn- finished-result [event-path nonce process]
  (try
    (if-not (Files/isRegularFile event-path (make-array LinkOption 0))
      {:raw-status "toolError"}
      (let [report (contract-json/read-path event-path)]
        (if-not (typed-replay/valid-event-report? report nonce)
          {:raw-status "toolError"}
          {:raw-status (classify-event report (.exitValue process))
           :tests (:tests report)
           :failed-test-ids (:failedTests report)
           :failure-signatures (:failureSignatures report)})))
    (finally
      (Files/deleteIfExists event-path))))

(defn- run-test-command [project signature-key selected-test-ids]
  (let [nonce (str (UUID/randomUUID))
        report-path (event-path project)
        process (.start (process-builder project nonce signature-key report-path selected-test-ids))]
    (if (.waitFor process 60 TimeUnit/SECONDS)
      (finished-result report-path nonce process)
      (do
        (.destroyForcibly process)
        (Files/deleteIfExists report-path)
        {:raw-status "timeout"}))))

(defn- require-baselines [project progress! total]
  (progress! "baseline" 0 total)
  (let [first-run (run-test-command project (new-signature-key) [])
        _ (progress! "baseline" 0 total)
        second-run (run-test-command project (new-signature-key) [])]
    (progress! "baseline" 0 total)
    (when-not (and (= "passed" (:raw-status first-run))
                   (= "passed" (:raw-status second-run))
                   (= (:tests first-run) (:tests second-run)))
      (throw (ex-info "baseline tests did not pass twice" {:error :baselineFailed :exit-code 4})))
    (:tests first-run)))

(defn- write-source [candidate content]
  (Files/write ^Path (:path candidate) (.getBytes ^String content "UTF-8")
               (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                       StandardOpenOption/WRITE])))

(defn- run-mutant [project candidate signature-key selected-test-ids]
  (write-source candidate (backend-source/mutate-source-text (:content candidate) (:site candidate)))
  (try
    (run-test-command project signature-key selected-test-ids)
    (finally
      (write-source candidate (:content candidate)))))

(defn- assertion-outcome [project candidate signature-key first-run progress! completed total]
  (let [selected-test-ids (:failed-test-ids first-run)
        control (run-test-command project signature-key selected-test-ids)
        _ (progress! "candidate" completed total)
        replay (run-mutant project candidate signature-key selected-test-ids)]
    (progress! "candidate" completed total)
    {:id (:id candidate)
     :rawStatus "assertionFailure"
     :controlPassed (typed-replay/matching-selected-control? selected-test-ids control)
     :replayMatched (typed-replay/matching-assertion-replay? first-run replay)}))

(defn- test-candidate [project candidate progress! completed total]
  (progress! "candidate" completed total)
  (let [signature-key (new-signature-key)
        first-run (run-mutant project candidate signature-key [])]
    (progress! "candidate" completed total)
    (if (= "assertionFailure" (:raw-status first-run))
      (assertion-outcome project candidate signature-key first-run progress! completed total)
      {:id (:id candidate) :rawStatus (:raw-status first-run)})))

(defn- test-candidates [project candidates progress!]
  (let [total (count candidates)]
    (mapv (fn [index candidate]
            (let [outcome (test-candidate project candidate progress! index total)]
              (progress! "candidate" (inc index) total)
              outcome))
          (range total) candidates)))

(defn- public-candidate [candidate]
  {:id (:id candidate) :operator (:operator candidate)})

(defn- write-machine-report [project source-inventory candidates outcomes]
  (let [path (config/resolve-report project :mutation)
        report {:schemaVersion bridge/report-schema
                :backend {:name bridge/backend-name :sourceCommit bridge/backend-commit}
                :operatorInventory bridge/operator-inventory
                :sourceInventory source-inventory
                :candidates (mapv public-candidate candidates)
                :outcomes outcomes
                :unauthorizedExclusion 0}]
    (Files/write path (contract-json/write-bytes report)
                 (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                         StandardOpenOption/WRITE]))))

(defn run! [arguments]
  (let [options (parse-arguments arguments)
        project (config/load-project (:project options) (:config options) (:module options))
        progress! (progress/new-emitter)
        _ (progress! "started" 0 0)
        discovery (discover project)
        candidates (:candidates discovery)
        total (count candidates)]
    (progress! "discovery" 0 total)
    (require-baselines project progress! total)
    (let [outcomes (test-candidates project candidates progress!)]
      (write-machine-report project (:source-inventory discovery) candidates outcomes)
      (progress! "completed" total total))))

(defn -main [& arguments]
  (try
    (run! (vec arguments))
    (shutdown-agents)
    (System/exit 0)
    (catch Exception error
      (shutdown-agents)
      (System/exit (or (:exit-code (ex-data error)) 6)))))
