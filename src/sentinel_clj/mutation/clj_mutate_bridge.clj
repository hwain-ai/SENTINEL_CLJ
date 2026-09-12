(ns sentinel-clj.mutation.clj-mutate-bridge
  (:require [sentinel-clj.crap.models :as models]
            [sentinel-clj.mutation.gate :as gate]))

(def backend-name "clj-mutate")
(def backend-commit "e27dd5df63c4efdd66438587d1c5f49e73661b69")
(def report-schema "sentinel-clj-mutate-report-v1")
(def operator-inventory
  ["arithmetic" "boolean" "comparison" "conditional" "constant" "equality"])

(def raw-state-map
  {"passed" :survived
   "uncovered" :uncovered
   "timeout" :timedOut
   "compileError" :compileError
   "runtimeError" :runtimeError
   "pending" :pending
   "ignored" :ignored
   "toolError" :toolError})

(defn- fail [code message]
  (throw (models/contract-error code message)))

(defn- require-exact-keys [value expected code]
  (when-not (and (map? value) (= expected (set (keys value))))
    (fail code "bridge report shape is invalid"))
  value)

(defn- require-backend [backend]
  (require-exact-keys backend #{:name :sourceCommit} :backendIdentityInvalid)
  (when-not (= {:name backend-name :sourceCommit backend-commit} backend)
    (fail :backendIdentityMismatch "bridge report backend identity does not match the lock")))

(defn- require-operators [operators]
  (when-not (= operator-inventory operators)
    (fail :operatorInventoryMismatch "bridge report operator inventory does not match the lock")))

(defn- candidate-record [candidate]
  (require-exact-keys candidate #{:id :operator} :candidateShapeInvalid)
  (when-not (some #{(:operator candidate)} operator-inventory)
    (fail :unknownMutationOperator "candidate has an unknown operator"))
  (:id candidate))

(def source-identity-keys #{:moduleRelativePath :sourceDigest})
(def source-inventory-keys #{:moduleRelativePath :sourceDigest :candidateIds})

(defn- valid-digest? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- require-source-identity [source expected-keys]
  (require-exact-keys source expected-keys :sourceInventoryShapeInvalid)
  (when-not (and (models/unicode-scalar-string? (:moduleRelativePath source))
                 (not (empty? (:moduleRelativePath source)))
                 (not (.contains ^String (:moduleRelativePath source) "\u0000"))
                 (valid-digest? (:sourceDigest source)))
    (fail :sourceInventoryShapeInvalid "source inventory identity is invalid"))
  source)

(defn- require-source-inventory [reported expected candidate-ids]
  (when-not (and (vector? reported) (vector? expected) (seq expected))
    (fail :sourceInventoryShapeInvalid "source inventories must be nonempty arrays"))
  (doseq [source expected]
    (require-source-identity source source-identity-keys))
  (doseq [source reported]
    (require-source-identity source source-inventory-keys)
    (when-not (vector? (:candidateIds source))
      (fail :sourceInventoryShapeInvalid "source candidate IDs must be an array")))
  (let [reported-identities (mapv #(select-keys % source-identity-keys) reported)
        reported-candidate-ids (vec (mapcat :candidateIds reported))]
    (when-not (= expected reported-identities)
      (fail :sourceInventoryMismatch "reported production source inventory is incomplete"))
    (when-not (= candidate-ids reported-candidate-ids)
      (fail :candidateSourceJoinMismatch "candidate plan is not joined to its source inventory")))
  expected)

(defn- killed-record [outcome]
  (require-exact-keys outcome
                      #{:id :rawStatus :controlPassed :replayMatched}
                      :killProofInvalid)
  (when-not (and (true? (:controlPassed outcome))
                 (true? (:replayMatched outcome)))
    (fail :killProofInvalid "assertion kill lacks a passing control or matching replay"))
  {:candidate-id (:id outcome) :status :killed})

(defn- non-killed-record [outcome]
  (require-exact-keys outcome #{:id :rawStatus} :rawOutcomeShapeInvalid)
  (if-let [status (raw-state-map (:rawStatus outcome))]
    {:candidate-id (:id outcome) :status status}
    (fail :unknownRawState "backend returned an unknown raw mutation state")))

(defn- outcome-record [outcome]
  (when-not (map? outcome)
    (fail :rawOutcomeShapeInvalid "raw outcome must be an object"))
  (if (= "assertionFailure" (:rawStatus outcome))
    (killed-record outcome)
    (non-killed-record outcome)))

(defn normalize-report [report expected-source-inventory]
  (require-exact-keys report
                      #{:schemaVersion :backend :operatorInventory :sourceInventory :candidates
                        :outcomes :unauthorizedExclusion}
                      :backendReportShapeInvalid)
  (when-not (= report-schema (:schemaVersion report))
    (fail :backendReportSchemaInvalid "unsupported bridge report schema"))
  (require-backend (:backend report))
  (require-operators (:operatorInventory report))
  (when-not (and (vector? (:candidates report)) (vector? (:outcomes report)))
    (fail :backendReportShapeInvalid "candidate and outcome inventories must be arrays"))
  (let [candidate-ids (mapv candidate-record (:candidates report))
        source-inventory (require-source-inventory (:sourceInventory report)
                                                   expected-source-inventory
                                                   candidate-ids)
        records (mapv outcome-record (:outcomes report))
        gate-result (gate/evaluate-records candidate-ids records (:unauthorizedExclusion report))]
    {:candidate-ids candidate-ids
     :source-inventory source-inventory
     :records records
     :operator-inventory operator-inventory
     :gate gate-result}))
