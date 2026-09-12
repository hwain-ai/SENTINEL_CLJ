(ns sentinel-clj.crap.semantic-site
  (:require [clojure.string :as string]
            [sentinel-clj.crap.models :as models])
  (:import [java.nio.charset StandardCharsets]
           [java.util.regex Pattern]))

(defn- normalized-symbol [value]
  (let [text (str value)]
    (cond
      (re-matches #"p([0-9]+)__[0-9]+#" text)
      (str "%" (second (re-matches #"p([0-9]+)__[0-9]+#" text)))

      (re-matches #"rest__[0-9]+#" text) "%&"
      :else text)))

(declare canonical-value)

(defn- canonical-map [value]
  (->> value
       (map (fn [[key item]] [(canonical-value key) (canonical-value item)]))
       (sort-by pr-str)
       vec))

(defn- canonical-set [value]
  (->> value (map canonical-value) (sort-by pr-str) vec))

(defn- canonical-collection [value]
  (cond
    (list? value) [:list (mapv canonical-value value)]
    (vector? value) [:vector (mapv canonical-value value)]
    (map? value) [:map (canonical-map value)]
    :else [:set (canonical-set value)]))

(defn- canonical-scalar [value]
  (cond
    (instance? Pattern value) [:regex (.pattern ^Pattern value) (.flags ^Pattern value)]
    (char? value) [:character (int value)]
    (string? value) [:string value]
    (number? value) [:number (str value)]
    (boolean? value) [:boolean value]
    (nil? value) [:nil]
    :else [:unsupported (.getName (class value))]))

(defn canonical-value [value]
  (cond
    (symbol? value) [:symbol (normalized-symbol value)]
    (keyword? value) [:keyword (str value)]
    (coll? value) (canonical-collection value)
    :else (canonical-scalar value)))

(defn semantic-digest [value]
  (models/sha256-string (pr-str (canonical-value value))))

(defn callable-id [descriptor]
  (str "clj-semantic-site-v1:" (semantic-digest descriptor)))

(defn- valid-callable-id? [value]
  (and (models/unicode-scalar-string? value)
       (not (empty? value))
       (not (string/includes? value "\u0000"))))

(defn- parse-risk [row]
  (let [unknown? (contains? row :unknown-reason)
        numerator? (contains? row :numerator)
        denominator? (contains? row :denominator)]
    (cond
      (and unknown? (not numerator?) (not denominator?)
           (valid-callable-id? (:unknown-reason row)))
      (assoc row :known-risk? false)

      (and (not unknown?) numerator? denominator?)
      (let [numerator (models/parse-unsigned-integer (:numerator row) :crapFractionInvalid "numerator")
            denominator (models/parse-unsigned-integer (:denominator row) :crapFractionInvalid "denominator")]
        (when (zero? denominator)
          (throw (models/contract-error :crapFractionInvalid "denominator must be positive")))
        (assoc row :known-risk? true :numerator numerator :denominator denominator))

      :else
      (throw (models/contract-error :crapRowInvalid "row must have exactly one risk representation")))))

(defn- validate-row [row]
  (when-not (map? row)
    (throw (models/contract-error :crapRowInvalid "row must be a map")))
  (models/require-module-relative-path (:module-relative-path row))
  (models/require-safe-integer (:source-start-byte row) 0 :sourceStartByteInvalid "sourceStartByte")
  (when-not (valid-callable-id? (:callable-id row))
    (throw (models/contract-error :callable-id-invalid "callableId must contain Unicode scalar values")))
  (parse-risk row))

(defn- compare-utf8 [left right]
  (let [left-bytes (.getBytes ^String left StandardCharsets/UTF_8)
        right-bytes (.getBytes ^String right StandardCharsets/UTF_8)
        shared (min (alength left-bytes) (alength right-bytes))]
    (loop [index 0]
      (if (= index shared)
        (compare (alength left-bytes) (alength right-bytes))
        (let [difference (compare (bit-and 0xff (aget left-bytes index))
                                  (bit-and 0xff (aget right-bytes index)))]
          (if (zero? difference)
            (recur (inc index))
            difference))))))

(defn- compare-risk [left right]
  (cond
    (not= (:known-risk? left) (:known-risk? right))
    (if (:known-risk? left) 1 -1)

    (not (:known-risk? left)) 0
    :else
    (compare (*' (:numerator right) (:denominator left))
             (*' (:numerator left) (:denominator right)))))

(defn- compare-identities [left right]
  (let [path-order (compare-utf8 (:module-relative-path left) (:module-relative-path right))
        start-order (compare (:source-start-byte left) (:source-start-byte right))]
    (cond
      (not (zero? path-order)) path-order
      (not (zero? start-order)) start-order
      :else (compare-utf8 (:callable-id left) (:callable-id right)))))

(defn- compare-rows [left right]
  (let [risk-order (compare-risk left right)]
    (if (zero? risk-order)
      (compare-identities left right)
      risk-order)))

(defn- reject-duplicate-identities [rows]
  (let [identity (fn [row] [(:module-relative-path row)
                            (:source-start-byte row)
                            (:callable-id row)])]
    (when (some #(> (count %) 1) (vals (group-by identity rows)))
      (throw (models/contract-error :identityAmbiguous "two rows have the same final identity")))))

(defn sort-rows [rows]
  (when-not (vector? rows)
    (throw (models/contract-error :crapRowsInvalid "rows must be a vector")))
  (let [validated (mapv validate-row rows)]
    (reject-duplicate-identities validated)
    (vec (sort compare-rows validated))))
