(ns fixtures.runner.two-assertion-failures
  (:require [clojure.test :refer [deftest is]]))

(deftest same-test
  (is (= "private-expected-one" "private-actual-one"))
  (is (= "private-expected-two" "private-actual-two")))

(deftest other-test
  (is true))
