(ns sentinel-clj.test-runner
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :as test]))

(def inventory-resource "fixtures/test-inventory/crap.edn")

(def test-namespaces
  '[sentinel-clj.test-runner-test
    sentinel-clj.json-test
    sentinel-clj.crap.formula-test
    sentinel-clj.crap.analyzer-test
    sentinel-clj.crap.coverage-test
    sentinel-clj.crap.semantic-site-test
    sentinel-clj.rendering.canonical-decimal-test
    sentinel-clj.runner.clojure-test-events-test
    sentinel-clj.mutation.progress-test
    sentinel-clj.mutation.typed-replay-test
    sentinel-clj.mutation.gate-test
    sentinel-clj.mutation.clj-mutate-bridge-test
    sentinel-clj.project-cli-test])

(defn- supported-arguments? [arguments]
  (or (empty? arguments)
      (= ["--include-id" "crap"] arguments)))

(defn- load-tests []
  (doseq [namespace-symbol test-namespaces]
    (require namespace-symbol)))

(defn- test-var-ids []
  (->> test-namespaces
       (mapcat (fn [namespace-symbol]
                 (for [[var-symbol test-var] (ns-publics namespace-symbol)
                       :when (:test (meta test-var))]
                   (str namespace-symbol "/" var-symbol))))
       sort
       vec))

(defn- fail-inventory [message details]
  (throw (ex-info message (assoc details :type :invalid-test-inventory))))

(defn- duplicates [values]
  (->> values
       frequencies
       (keep (fn [[value count]]
               (when (> count 1) value)))
       sort
       vec))

(defn validate-inventory! [inventory actual-namespaces actual-test-var-ids]
  (when-not (= #{:schema-version :selector :namespaces :test-var-ids}
               (set (keys inventory)))
    (fail-inventory "test inventory has unexpected keys" {}))
  (when-not (= 1 (:schema-version inventory))
    (fail-inventory "test inventory schema-version must be 1" {}))
  (when-not (= "crap" (:selector inventory))
    (fail-inventory "test inventory selector must be crap" {}))
  (let [expected-namespaces (:namespaces inventory)
        expected-ids (:test-var-ids inventory)
        duplicate-ids (duplicates expected-ids)]
    (when-not (and (vector? expected-namespaces)
                   (every? string? expected-namespaces)
                   (seq expected-ids)
                   (vector? expected-ids)
                   (every? string? expected-ids))
      (fail-inventory "test inventory requires nonempty string ID vectors" {}))
    (when (seq (duplicates expected-namespaces))
      (fail-inventory "test inventory contains duplicate namespaces"
                      {:duplicates (duplicates expected-namespaces)}))
    (when (seq duplicate-ids)
      (fail-inventory "test inventory contains duplicate test IDs"
                      {:duplicates duplicate-ids}))
    (when-not (= expected-ids (vec (sort expected-ids)))
      (fail-inventory "test inventory IDs must be sorted" {}))
    (when-not (= expected-namespaces (vec actual-namespaces))
      (fail-inventory "test namespace inventory does not match discovery"
                      {:expected expected-namespaces
                       :actual (vec actual-namespaces)}))
    (when-not (= expected-ids (vec actual-test-var-ids))
      (fail-inventory "test ID inventory does not match discovery"
                      {:expected expected-ids
                       :actual (vec actual-test-var-ids)}))))

(defn- read-inventory []
  (let [resource (io/resource inventory-resource)]
    (when-not resource
      (fail-inventory "test inventory resource is missing"
                      {:resource inventory-resource}))
    (edn/read-string
     {:readers {}
      :default (fn [tag _]
                 (fail-inventory "test inventory contains a tagged value"
                                 {:tag (str tag)}))}
     (slurp resource :encoding "UTF-8"))))

(defn- run-suite []
  (let [ids (test-var-ids)
        namespace-ids (mapv str test-namespaces)
        _ (validate-inventory! (read-inventory) namespace-ids ids)
        result (apply test/run-tests test-namespaces)]
    (println (str "SENTINEL_CLJ tests discovered=" (count ids)
                  " executed=" (:test result)))
    (if (and (= (count ids) (:test result))
             (zero? (+ (:fail result) (:error result))))
      0
      1)))

(defn -main [& arguments]
  (if-not (supported-arguments? (vec arguments))
    (do
      (binding [*out* *err*]
        (println "usage: test runner [--include-id crap]"))
      (System/exit 2))
    (do
      (load-tests)
      (System/exit (run-suite)))))
