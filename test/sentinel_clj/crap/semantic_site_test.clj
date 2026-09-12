(ns sentinel-clj.crap.semantic-site-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sentinel-clj.crap.semantic-site :as site]))

(defn- golden []
  (json/read-str (slurp (io/resource "golden/crap/stable-sort-v1.json")) :key-fn keyword))

(defn- normalize-row [row]
  (cond-> {:module-relative-path (:moduleRelativePath row)
           :source-start-byte (:sourceStartByte row)
           :callable-id (:callableId row)}
    (:unknownReason row) (assoc :unknown-reason (:unknownReason row))
    (:numerator row) (assoc :numerator (:numerator row)
                            :denominator (:denominator row))))

(defn- error-code [thunk]
  (:error (ex-data (try (thunk) (catch Exception error error)))))

(deftest shared-stable-sort-vectors-use-exact-risk-and-utf8-bytes
  (doseq [{case-id :id rows :rows expected :expectedCallableIds} (:cases (golden))]
    (testing case-id
      (is (= expected
             (mapv :callable-id (site/sort-rows (mapv normalize-row rows))))))))

(deftest shared-invalid-sort-vectors-fail-closed
  (doseq [{case-id :id rows :rows expected :expectedError} (:invalidCases (golden))]
    (testing case-id
      (is (= (keyword expected)
             (error-code #(site/sort-rows (mapv normalize-row rows))))))))

(deftest module-path-and-callable-id-require-valid-unicode-scalars
  (is (= :module-relative-path-invalid
         (error-code #(site/sort-rows [{:module-relative-path "../escape.clj"
                                        :source-start-byte 0
                                        :callable-id "x"
                                        :numerator "1"
                                        :denominator "1"}]))))
  (is (= :callable-id-invalid
         (error-code #(site/sort-rows [{:module-relative-path "src/a.clj"
                                        :source-start-byte 0
                                        :callable-id (str (char 0xD800))
                                        :numerator "1"
                                        :denominator "1"}])))))
