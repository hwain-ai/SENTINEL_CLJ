(ns sentinel-clj.orchestrator
  (:require [clojure.string :as string]
            [sentinel-clj.config :as config]
            [sentinel-clj.evidence :as evidence]
            [sentinel-clj.evidence.contract :as evidence-contract]
            [sentinel-clj.history :as history]
            [sentinel-clj.mutation.backend-lock :as backend-lock]
            [sentinel-clj.mutation.clj-mutate-bridge :as bridge]
            [sentinel-clj.quality :as quality]
            [sentinel-clj.workspace :as workspace])
  (:import [java.nio.file Files LinkOption Path]
           [java.util UUID]))

(defn- public-result [evidence component-results]
  (merge {:schemaVersion "sentinel-quality-result-v1"
          :language "clojure"
          :run {:runId (:runId evidence)
                :correlationId (:correlationId evidence)
                :terminalStatus (:terminalStatus evidence)
                :exitCode (:exitCode evidence)}}
         component-results))

(defn- quality-exit [components]
  (cond
    (pos? (get-in components [:mutation :toolError] 0)) 6
    (every? true? (map :pass (vals components))) 0
    :else 2))

(defn- run-components [command project]
  (let [crap-result (when (#{"crap" "check"} command) (quality/run-crap project))
        mutation-result (when (#{"mutation" "check"} command) (quality/run-mutation project))]
    (cond-> {}
      crap-result (assoc :crap crap-result)
      mutation-result (assoc :mutation mutation-result))))

(defn- prepare-strict [command project]
  (when (#{"crap" "check"} command)
    (workspace/execute-tool! project :coverage 5))
  (when (#{"mutation" "check"} command)
    (workspace/execute-tool! project :mutation 6))
  (run-components command project))

(defn- execute-components [command project mode]
  (if (= "strict" mode)
    (workspace/with-snapshot project #(prepare-strict command %))
    (run-components command project)))

(defn- commit-success [command options project raw-results]
  (let [components (into {} (map (fn [[key value]] [key (:public value)]) raw-results))
        findings (mapcat :findings (vals raw-results))
        exit-code (quality-exit components)
        correlation-id (or (:correlation-id options) (str (UUID/randomUUID)))
        committed (evidence/commit! (:project-root project) command correlation-id
                                    (:mode options) components findings exit-code)]
    {:exit-code exit-code :body (public-result committed components)}))

(defn- failure-component [command]
  (evidence-contract/empty-components command))

(defn- failure-exit [command error]
  (or (:exit-code (ex-data error))
      (if (= "crap" command) 5 6)))

(defn- safe-diagnostic-code [error]
  (string/replace (name (or (:error (ex-data error)) :backendError))
                  #"-([A-Za-z0-9])"
                  (fn [[_ character]] (string/upper-case character))))

(defn- commit-failure [command options project error]
  (let [code (safe-diagnostic-code error)
        components (failure-component command)
        findings [{:stable-id code :finding-class "backend"
                   :defect-kind code :status "failed"}]
        exit-code (failure-exit command error)
        correlation-id (or (:correlation-id options) (str (UUID/randomUUID)))
        committed (evidence/commit! (:project-root project) command correlation-id
                                    (:mode options) components findings exit-code)]
    {:exit-code exit-code
     :body (assoc (public-result committed components) :diagnostics [code])}))

(defn run-quality [command options]
  (let [project (config/load-project (:project options) (:config options) (:module options))
        outcome (try
                  {:results (execute-components command project (:mode options))}
                  (catch clojure.lang.ExceptionInfo error {:error error}))]
    (if-let [error (:error outcome)]
      (commit-failure command options project error)
      (commit-success command options project (:results outcome)))))

(defn doctor [options]
  (let [project (config/load-project (:project options) (:config options) (:module options))]
    (backend-lock/verify!)
    {:exit-code 0
     :body {:schemaVersion "sentinel-doctor-v1"
            :language "clojure"
            :module (get-in project [:module :id])
            :productionFiles (count (:production-files project))
            :backend {:name bridge/backend-name :sourceCommit bridge/backend-commit}
            :passed true}}))

(defn history [options]
  (let [project-root (.toRealPath (.toAbsolutePath (Path/of (:project options) (make-array String 0)))
                                  (make-array LinkOption 0))]
    {:exit-code 0 :body (history/read-history project-root (:repeated options))}))
