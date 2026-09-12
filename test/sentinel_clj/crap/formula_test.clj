(ns sentinel-clj.crap.formula-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sentinel-clj.crap.formula :as formula]))

(defn- golden []
  (json/read-str (slurp (io/resource "golden/crap/formula-v1.json")) :key-fn keyword))

(defn- error-code [thunk]
  (:error (ex-data (try (thunk) (catch Exception error error)))))

(deftest matches-every-shared-formula-vector
  (doseq [{case-id :id input :input expected :expected} (:formulaCases (golden))]
    (testing case-id
      (let [result (formula/calculate (:cyclomaticComplexity input)
                                      (:coveredUnits input)
                                      (:totalUnits input))]
        (is (= (:numerator expected) (str (:numerator result))))
        (is (= (:denominator expected) (str (:denominator result))))
        (is (= (:decimal expected) (:decimal result)))
        (is (= (:pass expected) (:pass? result)))))))

(deftest rejects-every-shared-invalid-vector
  (doseq [{case-id :id input :input expected :error} (:invalidCases (golden))]
    (testing case-id
      (is (= (keyword expected)
             (error-code #(formula/calculate (:cyclomaticComplexity input)
                                             (:coveredUnits input)
                                             (:totalUnits input))))))))

(deftest keeps-the-exact-reduced-ratio-at-the-gate
  (let [result (formula/calculate 4 3 4)]
    (is (= 17 (:numerator result)))
    (is (= 4 (:denominator result)))
    (is (true? (:pass? result))))
  (is (true? (:pass? (formula/calculate 8 1 1))))
  (is (false? (:pass? (formula/calculate 9 1 1)))))
