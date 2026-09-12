(ns sentinel-clj.history
  (:require [sentinel-clj.evidence :as evidence])
  (:import [java.nio.file Path]))

(defn- occurrence [evidence finding]
  {:runId (:runId evidence)
   :commitSequence (:commitSequence evidence)
   :completedAtUtc (:completedAtUtc evidence)
   :finding finding})

(defn- summarize-group [[fingerprint observations]]
  (let [ordered (sort-by #(bigint (:commitSequence %)) observations)
        first-observation (first ordered)
        last-observation (last ordered)
        finding (:finding first-observation)
        count (count (set (map :runId observations)))]
    {:fingerprint fingerprint
     :findingClass (:findingClass finding)
     :defectKind (:defectKind finding)
     :normalizedStatus (:normalizedStatus finding)
     :observationCount count
     :firstObservedAt (:completedAtUtc first-observation)
     :lastObservedAt (:completedAtUtc last-observation)
     :repeated (>= count 2)}))

(defn read-history [^Path project-root repeated-only]
  (let [completed (evidence/read-completed! project-root)
        observations (for [run completed finding (:findings run)] (occurrence run finding))
        findings (->> observations
                      (group-by #(get-in % [:finding :fingerprint]))
                      (map summarize-group)
                      (filter #(or (not repeated-only) (:repeated %)))
                      (sort-by :fingerprint)
                      vec)]
    {:schemaVersion "sentinel-history-v1"
     :language "clojure"
     :completedRuns (count completed)
     :findings findings}))
