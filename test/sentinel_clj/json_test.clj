(ns sentinel-clj.json-test
  (:require [clojure.test :refer [deftest is]]
            [sentinel-clj.json :as contract-json])
  (:import [java.nio.file Files StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(deftest canonical-json-has-one-byte-representation
  (let [value {:z [true nil 2]
               :a "é/한\n"
               :m {"quote\"" "\\"}}
        expected "{\"a\":\"é/한\\u000a\",\"m\":{\"quote\\\"\":\"\\\\\"},\"z\":[true,null,2]}\n"]
    (is (= expected (String. (contract-json/write-canonical-bytes value) "UTF-8")))))

(deftest canonical-reader-rejects-extra-whitespace
  (let [path (Files/createTempFile "sentinel-clj-canonical-" ".json"
                                   (make-array FileAttribute 0))]
    (Files/write path (.getBytes "{\"value\":1}\n " "UTF-8")
                 (into-array java.nio.file.OpenOption
                             [StandardOpenOption/TRUNCATE_EXISTING
                              StandardOpenOption/WRITE]))
    (let [error (try (contract-json/read-canonical-path path) nil
                     (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :jsonCanonicalInvalid (:error (ex-data error)))))))

(deftest canonical-writer-rejects-colliding-object-keys
  (let [error (try (contract-json/write-canonical-bytes {:name 1 "name" 2}) nil
                   (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :jsonCanonicalInvalid (:error (ex-data error))))))

(deftest canonical-writer-rejects-non-json-values
  (let [error (try (contract-json/write-canonical-bytes {:value '(1 2)}) nil
                   (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :jsonCanonicalInvalid (:error (ex-data error))))))
