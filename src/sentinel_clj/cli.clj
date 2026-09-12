(ns sentinel-clj.cli
  (:require [clojure.data.json :as json]
            [sentinel-clj.orchestrator :as orchestrator])
  (:import [java.util UUID])
  (:gen-class))

(def commands #{"crap" "mutation" "check" "doctor" "history"})
(def value-options
  {"--project" :project
   "--config" :config
   "--module" :module
   "--format" :format
   "--correlation-id" :correlation-id})
(def config-errors
  #{:argumentInvalid :projectConfigShapeInvalid :projectInvalid
    :configPathInvalid :moduleSelectionInvalid :moduleRootInvalid
    :productionInventoryEmpty :reportPathInvalid})

(def usage
  "usage: sentinel-clj <crap|mutation|check|doctor|history> [--project PATH] [--config PATH] [--module ID] [--strict|--local] [--format text|json] [--correlation-id UUID] [--repeated]")

(defn- usage-error [code]
  {:exit-code 3 :body {:schemaVersion "sentinel-error-v1" :code code}})

(defn- option-value [arguments index option]
  (when (= (inc index) (count arguments))
    (throw (ex-info "option needs a value" {:error :argumentInvalid :option option})))
  (nth arguments (inc index)))

(defn- select-mode [options selected]
  (let [current (:mode options)]
    (when (and current (not= current selected))
      (throw (ex-info "strict and local conflict" {:error :argumentInvalid})))
    (assoc options :mode selected)))

(defn- apply-flag [options option]
  (case option
    "--repeated" (assoc options :repeated true)
    "--strict" (select-mode options "strict")
    "--local" (select-mode options "local")
    nil))

(defn- parse-options [arguments]
  (loop [index 0 options {:project "." :format "text"}]
    (if (= index (count arguments))
      (update options :mode #(or % "strict"))
      (let [option (nth arguments index)
            value-key (value-options option)
            flagged (apply-flag options option)]
        (cond
          value-key (recur (+ index 2)
                           (assoc options value-key (option-value arguments index option)))
          flagged (recur (inc index) flagged)
          :else (throw (ex-info "unknown option" {:error :argumentInvalid :option option})))))))

(defn- dispatch [command options]
  (case command
    ("crap" "mutation" "check") (orchestrator/run-quality command options)
    "doctor" (orchestrator/doctor options)
    "history" (orchestrator/history options)))

(defn- help-request? [arguments]
  (or (= ["--help"] arguments) (= ["-h"] arguments)))

(defn- valid-command? [arguments]
  (and (seq arguments) (commands (first arguments))))

(defn- error-exit [data code]
  (or (:exit-code data)
      (when (config-errors (:error data)) 3)
      (when (.startsWith ^String code "backend") 6)
      5))

(defn- exception-result [error]
  (let [data (ex-data error)
        code (name (or (:error data) :toolError))]
    {:exit-code (error-exit data code)
     :body {:schemaVersion "sentinel-error-v1" :code code}}))

(defn- valid-correlation-id? [value]
  (try
    (or (nil? value)
        (and (string? value) (= value (str (UUID/fromString value)))))
    (catch Exception _ false)))

(defn- execute-valid [arguments]
  (let [options (parse-options (subvec arguments 1))]
    (if (and (#{"text" "json"} (:format options))
             (valid-correlation-id? (:correlation-id options)))
      (dispatch (first arguments) options)
      (usage-error "argumentInvalid"))))

(defn execute [arguments]
  (try
    (cond
      (help-request? arguments) {:exit-code 0 :body {:schemaVersion "sentinel-help-v1" :usage usage}}
      (not (valid-command? arguments)) (usage-error "argumentInvalid")
      :else (execute-valid arguments))
    (catch clojure.lang.ExceptionInfo error (exception-result error))
    (catch Exception _
      {:exit-code 1 :body {:schemaVersion "sentinel-error-v1" :code "toolError"}})))

(defn -main [& arguments]
  (let [{:keys [exit-code body]} (execute (vec arguments))]
    (println (json/write-str body))
    (flush)
    (shutdown-agents)
    (System/exit exit-code)))
