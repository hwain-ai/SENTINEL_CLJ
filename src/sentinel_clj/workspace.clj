(ns sentinel-clj.workspace
  (:require [sentinel-clj.config :as config]
            [sentinel-clj.mutation.progress :as progress])
  (:import [java.nio.file Files LinkOption OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(defn- state-entry? [^Path source-root ^Path path]
  (let [relative (.relativize source-root path)]
    (and (pos? (.getNameCount relative))
         (= ".sentinel" (str (.getName relative 0))))))

(defn- copy-entry [^Path source-root ^Path target-root ^Path source]
  (let [relative (.relativize source-root source)
        target (.resolve target-root relative)]
    (cond
      (zero? (.getNameCount relative)) nil
      (Files/isSymbolicLink source)
      (throw (ex-info "snapshot refuses symbolic links" {:error :snapshotSourceInvalid :exit-code 5}))
      (Files/isDirectory source (make-array LinkOption 0))
      (Files/createDirectories target (make-array FileAttribute 0))
      (Files/isRegularFile source (make-array LinkOption 0))
      (Files/copy source target (into-array java.nio.file.CopyOption [StandardCopyOption/COPY_ATTRIBUTES]))
      :else
      (throw (ex-info "snapshot refuses special files" {:error :snapshotSourceInvalid :exit-code 5})))))

(defn- copy-project [^Path source-root ^Path target-root]
  (with-open [paths (Files/walk source-root (make-array java.nio.file.FileVisitOption 0))]
    (doseq [path (iterator-seq (.iterator paths))
            :when (not (state-entry? source-root path))]
      (copy-entry source-root target-root path))))

(defn- delete-tree [^Path root]
  (when (Files/exists root (make-array LinkOption 0))
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
        (Files/delete path)))))

(defn- remap-project [project ^Path snapshot-root]
  (let [source-root ^Path (:project-root project)
        remap #(.resolve snapshot-root (.relativize source-root ^Path %))]
    (assoc project
           :project-root snapshot-root
           :config-file (remap (:config-file project))
           :module-root (remap (:module-root project))
           :production-files (mapv remap (:production-files project)))))

(defn with-snapshot [project operation]
  (let [snapshot-root (Files/createTempDirectory "sentinel-clj-snapshot-"
                                                 (make-array FileAttribute 0))]
    (try
      (copy-project (:project-root project) snapshot-root)
      (operation (remap-project project snapshot-root))
      (finally
        (delete-tree snapshot-root)))))

(defn- prepare-report-path [project tool-key]
  (let [report (config/resolve-report project tool-key)]
    (when (Files/exists report (make-array LinkOption 0))
      (when-not (and (Files/isRegularFile report (make-array LinkOption 0))
                     (not (Files/isSymbolicLink report)))
        (throw (ex-info "tool report path is unsafe" {:error :reportPathInvalid :exit-code 5})))
      (Files/delete report))
    report))

(def mutation-watchdog-limits
  {:startup-timeout-ms 120000
   :idle-timeout-ms 90000
   :absolute-timeout-ms 172800000})

(defn- start-process [project tool-key progress-contract]
  (let [command (get-in project [:module tool-key :command])
        builder (ProcessBuilder. ^java.util.List command)
        environment (.environment builder)]
    (.clear environment)
    (.put environment "PATH" "/usr/bin:/bin")
    (.put environment "LANG" "C.UTF-8")
    (.put environment "LC_ALL" "C.UTF-8")
    (doseq [[key value] (when progress-contract (progress/environment-values progress-contract))]
      (.put environment key value))
    (.directory builder (.toFile ^Path (:module-root project)))
    (.redirectOutput builder java.lang.ProcessBuilder$Redirect/DISCARD)
    (.redirectError builder java.lang.ProcessBuilder$Redirect/DISCARD)
    (.start builder)))

(defn- process-failure [process-exit default-exit]
  (case process-exit
    4 {:error :baselineFailed :exit-code 4}
    5 {:error :dependencyError :exit-code 5}
    6 {:error :backendError :exit-code 6}
    {:error :toolProcessFailed :exit-code default-exit}))

(defn- monotonic-millis []
  (quot (System/nanoTime) 1000000))

(defn- terminate-process! [^Process process]
  (.destroyForcibly process)
  (.waitFor process 5 TimeUnit/SECONDS))

(defn- timeout-data [status failure-exit]
  {:error (case status
            :startup-timeout :toolStartupTimedOut
            :idle-timeout :toolProgressStalled
            :absolute-timeout :toolAbsoluteTimedOut)
   :exit-code failure-exit})

(defn- advance-progress [state record now]
  (if-not record
    state
    (let [previous (:sequence state)
          observed (:sequence record)]
      (cond
        (< observed previous)
        (throw (ex-info "tool progress sequence regressed" {:error :toolProgressInvalid}))
        (= observed previous) state
        :else {:sequence observed :last-progress-at now}))))

(defn- wait-with-progress! [process progress-contract failure-exit]
  (let [started-at (monotonic-millis)]
    (try
      (loop [state {:sequence 0 :last-progress-at nil}]
        (if (.waitFor ^Process process 250 TimeUnit/MILLISECONDS)
          nil
          (let [now (monotonic-millis)
                next-state (advance-progress state (progress/read! progress-contract) now)
                status (progress/watchdog-status mutation-watchdog-limits started-at now
                                                  (:last-progress-at next-state))]
            (if (= :running status)
              (recur next-state)
              (throw (ex-info "tool process watchdog expired"
                              (timeout-data status failure-exit)))))))
      (catch Exception error
        (terminate-process! process)
        (if (= :progressInvalid (:error (ex-data error)))
          (throw (ex-info "tool progress is invalid"
                          {:error :toolProgressInvalid :exit-code failure-exit} error))
          (throw error))))))

(defn- wait-for-process! [process tool-key progress-contract failure-exit]
  (if (= :mutation tool-key)
    (wait-with-progress! process progress-contract failure-exit)
    (when-not (.waitFor ^Process process 300 TimeUnit/SECONDS)
      (terminate-process! process)
      (throw (ex-info "tool process timed out"
                      {:error :toolProcessTimedOut :exit-code failure-exit})))))

(defn execute-tool! [project tool-key failure-exit]
  (let [report (prepare-report-path project tool-key)
        progress-contract (when (= :mutation tool-key)
                            (progress/new-contract (:module-root project)))
        process (start-process project tool-key progress-contract)]
    (try
      (wait-for-process! process tool-key progress-contract failure-exit)
      (when-not (zero? (.exitValue process))
        (throw (ex-info "tool process failed"
                        (process-failure (.exitValue process) failure-exit))))
      (when-not (and (Files/isRegularFile report (make-array LinkOption 0))
                     (not (Files/isSymbolicLink report)))
        (throw (ex-info "tool did not create its report"
                        {:error :toolReportMissing :exit-code failure-exit})))
      report
      (finally
        (when progress-contract
          (Files/deleteIfExists (:path progress-contract)))))))
