(ns sentinel-clj.runner.clojure-test-events
  (:require [clojure.test :as test]
            [sentinel-clj.json :as contract-json])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardOpenOption]
           [java.util HexFormat]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def signature-schema "sentinel-clojure-failure-signature-v1")

(defn- require-signature-key [value]
  (when-not (and (string? value) (re-matches #"[0-9a-f]{64}" value))
    (throw (ex-info "failure signature key is invalid" {:error :signatureKeyInvalid})))
  (.parseHex (HexFormat/of) ^String value))

(defn- printable-value [value]
  (binding [*print-dup* false
            *print-length* nil
            *print-level* nil
            *print-meta* false]
    (pr-str value)))

(defn- actual-value [event]
  (let [actual (:actual event)]
    (if (and (= :error (:type event)) (instance? Throwable actual))
      (.getName (class actual))
      (printable-value actual))))

(defn- payload-fields [test-identifier contexts event]
  [signature-schema
   (name (:type event))
   test-identifier
   (str (count contexts))
   (printable-value (vec contexts))
   (str (:file event))
   (str (:line event))
   (str (:message event))
   (printable-value (:expected event))
   (actual-value event)])

(defn- update-field! [^Mac mac value]
  (let [content (.getBytes ^String value StandardCharsets/UTF_8)
        size (.array (doto (ByteBuffer/allocate Integer/BYTES)
                       (.putInt (alength content))))]
    (.update mac ^bytes size)
    (.update mac ^bytes content)))

(defn failure-signature [signature-key test-identifier contexts event]
  (when-not (#{:fail :error} (:type event))
    (throw (ex-info "event is not a typed failure" {:error :failureEventInvalid})))
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (require-signature-key signature-key) "HmacSHA256"))
    (doseq [field (payload-fields test-identifier contexts event)]
      (update-field! mac field))
    (.formatHex (HexFormat/of) (.doFinal mac))))

(defn- test-id [event]
  (when-let [test-var (or (:var event) (last test/*testing-vars*))]
    (let [metadata (meta test-var)]
      (str (ns-name (:ns metadata)) "/" (:name metadata)))))

(def ^:dynamic *event-state* nil)
(def ^:dynamic *signature-key* nil)

(defn- record-test! [event]
  (when-let [identifier (test-id event)]
    (swap! *event-state* update :tests conj identifier)))

(defn- record-failure! [event]
  (test/inc-report-counter :fail)
  (when-let [identifier (test-id event)]
    (let [signature (failure-signature *signature-key* identifier
                                       (vec (reverse test/*testing-contexts*)) event)]
      (swap! *event-state* update :failures conj
             {:test-id identifier :signature signature}))))

(defn- record-error! [event]
  (test/inc-report-counter :error)
  (when-let [identifier (test-id event)]
    (let [signature (failure-signature *signature-key* identifier
                                       (vec (reverse test/*testing-contexts*)) event)]
      (swap! *event-state* update :errors conj
             {:test-id identifier :signature signature}))))

(defn- report-event [event]
  (case (:type event)
    :begin-test-var (record-test! event)
    :pass (test/inc-report-counter :pass)
    :fail (record-failure! event)
    :error (record-error! event)
    nil))

(defn- event-report [nonce state]
  {:schemaVersion "sentinel-clojure-test-events-v2"
   :nonce nonce
   :tests (vec (sort (:tests state)))
   :failedTests (vec (sort (set (map :test-id (:failures state)))))
   :errorTests (vec (sort (set (map :test-id (:errors state)))))
   :failureSignatures (vec (sort (map :signature (:failures state))))
   :errorSignatures (vec (sort (map :signature (:errors state))))
   :summary {:test (count (:tests state))
             :fail (count (:failures state))
             :error (count (:errors state))}})

(defn- write-report [path report]
  (Files/write (Path/of path (make-array String 0))
               (contract-json/write-bytes report)
               (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                       StandardOpenOption/WRITE])))

(defn- split-test-selection [arguments]
  (loop [remaining arguments selected []]
    (if (= "--sentinel-test-id" (first remaining))
      (if-let [identifier (second remaining)]
        (recur (nnext remaining) (conj selected identifier))
        (throw (ex-info "test selector is incomplete" {:error :runnerContractInvalid})))
      {:selected selected :namespace-names remaining})))

(defn- test-var-inventory [namespace-symbols]
  (into {}
        (mapcat (fn [namespace-symbol]
                  (for [[var-symbol test-var] (ns-publics namespace-symbol)
                        :when (:test (meta test-var))]
                    [(str namespace-symbol "/" var-symbol) test-var])))
        namespace-symbols))

(defn- require-selected-vars [namespace-symbols selected]
  (let [inventory (test-var-inventory namespace-symbols)]
    (when-not (= (count selected) (count (set selected)))
      (throw (ex-info "test selectors must be unique" {:error :runnerContractInvalid})))
    (when-not (every? inventory selected)
      (throw (ex-info "test selector does not name a declared test var"
                      {:error :runnerContractInvalid})))
    (mapv inventory selected)))

(defn- execute-tests [namespace-symbols selected]
  (if (seq selected)
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/test-vars (require-selected-vars namespace-symbols selected)))
    (apply test/run-tests namespace-symbols)))

(defn- run-tests [namespace-names selected signature-key]
  (let [namespace-symbols (mapv symbol namespace-names)
        state (atom {:tests #{} :failures [] :errors []})]
    (doseq [namespace-symbol namespace-symbols] (require namespace-symbol))
    (binding [*event-state* state
              *signature-key* signature-key
              test/report report-event]
      (execute-tests namespace-symbols selected))
    @state))

(defn -main [& arguments]
  (let [[nonce-option nonce signature-option signature-key
         report-option report-path & runner-arguments] arguments
        {:keys [selected namespace-names]} (split-test-selection runner-arguments)]
    (when-not (and (= "--sentinel-nonce" nonce-option)
                   (= "--sentinel-signature-key" signature-option)
                   (= "--sentinel-report" report-option)
                   nonce signature-key report-path (seq namespace-names))
      (throw (ex-info "typed test runner contract is missing" {:error :runnerContractInvalid})))
    (require-signature-key signature-key)
    (let [state (run-tests namespace-names selected signature-key)
          failed? (or (seq (:failures state)) (seq (:errors state)))]
      (write-report report-path (event-report nonce state))
      (shutdown-agents)
      (System/exit (if failed? 1 0)))))
