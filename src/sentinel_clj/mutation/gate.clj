(ns sentinel-clj.mutation.gate
  (:require [sentinel-clj.crap.models :as models]))

(def states
  [:killed :survived :uncovered :timedOut :compileError
   :runtimeError :pending :ignored :toolError])

(def state-set (set states))

(defn- fail [code message]
  (throw (models/contract-error code message)))

(defn- require-count [value field]
  (when-not (integer? value)
    (fail (keyword (str field "NotInteger")) (str field " must be an integer")))
  (when-not (<= 0 value models/max-safe-integer)
    (fail (keyword (str field "OutOfRange")) (str field " is outside the JSON safe integer range")))
  value)

(defn- require-counts [counts]
  (when-not (map? counts)
    (fail :mutationCountsNotObject "mutation counts must be an object"))
  (when (seq (remove state-set (keys counts)))
    (fail :unknownMutationState "mutation counts contain an unknown state"))
  (when (seq (remove #(contains? counts %) states))
    (fail :missingMutationState "mutation counts are missing a required state"))
  (doseq [state states]
    (require-count (get counts state) "mutationCount"))
  counts)

(defn- reduced-rate [killed in-scope]
  (when (pos? in-scope)
    (let [divisor (.gcd (biginteger killed) (biginteger in-scope))]
      {:numerator (quot killed divisor)
       :denominator (quot in-scope divisor)})))

(defn evaluate-counts [counts in-scope unauthorized-exclusion]
  (require-counts counts)
  (require-count in-scope "inScope")
  (require-count unauthorized-exclusion "unauthorizedExclusion")
  (when-not (= in-scope (reduce + (map counts states)))
    (fail :stateCountMismatch "mutation state counts do not equal in-scope count"))
  (let [killed (:killed counts)]
    {:counts counts
     :in-scope in-scope
     :unauthorized-exclusion unauthorized-exclusion
     :kill-rate (reduced-rate killed in-scope)
     :pass? (and (pos? in-scope)
                 (= killed in-scope)
                 (every? zero? (map counts (rest states)))
                 (zero? unauthorized-exclusion))}))

(defn- require-candidate-id [candidate-id]
  (when-not (and (models/unicode-scalar-string? candidate-id)
                 (not (empty? candidate-id))
                 (not (.contains ^String candidate-id "\u0000")))
    (fail :candidateIdInvalid "candidate ID must be a nonempty Unicode scalar string"))
  candidate-id)

(defn- require-distinct [values error-code]
  (when-not (= (count values) (count (set values)))
    (fail error-code "mutation inventory contains duplicate IDs")))

(defn evaluate-records [candidate-ids records unauthorized-exclusion]
  (when-not (sequential? candidate-ids)
    (fail :candidatePlanNotIterable "candidate plan must be sequential"))
  (when-not (sequential? records)
    (fail :mutationRecordsNotIterable "mutation records must be sequential"))
  (doseq [candidate-id candidate-ids] (require-candidate-id candidate-id))
  (require-distinct candidate-ids :duplicateCandidateId)
  (let [record-ids (mapv :candidate-id records)]
    (doseq [record records]
      (when-not (and (map? record) (= #{:candidate-id :status} (set (keys record))))
        (fail :mutationRecordTypeInvalid "mutation record shape is invalid"))
      (require-candidate-id (:candidate-id record))
      (when-not (state-set (:status record))
        (fail :unknownMutationState "mutation record has an unknown state")))
    (require-distinct record-ids :duplicateMutationResult)
    (when-not (= (set candidate-ids) (set record-ids))
      (fail :candidateResultSetMismatch "candidate and outcome ID sets differ"))
    (let [counts (merge (zipmap states (repeat 0)) (frequencies (map :status records)))]
      (evaluate-counts counts (count candidate-ids) unauthorized-exclusion))))
