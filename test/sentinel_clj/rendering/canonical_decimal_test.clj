(ns sentinel-clj.rendering.canonical-decimal-test
  (:require [clojure.test :refer [deftest is testing]]
            [sentinel-clj.rendering.canonical-decimal :as decimal]))

(deftest renders-canonical-decimals-with-integer-only-half-even-rounding
  (testing "finite and repeating fractions"
    (is (= "4.25" (decimal/render 17 4)))
    (is (= "0.333333333333" (decimal/render 1 3))))
  (testing "exact halfway values use the even retained digit"
    (is (= "0.123456789012" (decimal/render 246913578025 2000000000000)))
    (is (= "0.123456789014" (decimal/render 246913578027 2000000000000))))
  (testing "carry and trailing zeros are canonical"
    (is (= "1" (decimal/render 1999999999999 2000000000000)))
    (is (= "0" (decimal/render 0 19)))
    (is (= "42" (decimal/render 42 1)))))

(deftest rejects-invalid-fractions
  (is (= :numerator-out-of-range
         (:error (ex-data (try (decimal/render -1 1) (catch Exception error error))))))
  (is (= :denominator-out-of-range
         (:error (ex-data (try (decimal/render 1 0) (catch Exception error error)))))))
