(ns sentinel-clj.test-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [sentinel-clj.test-runner :as runner]))

(def valid-inventory
  {:schema-version 1
   :selector "crap"
   :namespaces ["sentinel-clj.example-test"]
   :test-var-ids ["sentinel-clj.example-test/works"]})

(deftest inventory-validation-rejects-zero-duplicate-missing-and-extra-test-ids
  (testing "the expected inventory cannot be empty"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"nonempty"
         (runner/validate-inventory!
          (assoc valid-inventory :test-var-ids [])
          ["sentinel-clj.example-test"]
          []))))
  (testing "the expected inventory cannot contain a duplicate"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate"
         (runner/validate-inventory!
          (assoc valid-inventory
                 :test-var-ids ["sentinel-clj.example-test/works"
                                "sentinel-clj.example-test/works"])
          ["sentinel-clj.example-test"]
          ["sentinel-clj.example-test/works"]))))
  (testing "missing and extra discovered IDs are both rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"does not match"
         (runner/validate-inventory!
          valid-inventory
          ["sentinel-clj.example-test"]
          ["sentinel-clj.example-test/different"]))))
  (testing "the exact namespace and test ID set is accepted"
    (is (nil?
         (runner/validate-inventory!
          valid-inventory
          ["sentinel-clj.example-test"]
          ["sentinel-clj.example-test/works"])))))
