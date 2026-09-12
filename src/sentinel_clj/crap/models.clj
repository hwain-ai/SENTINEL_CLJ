(ns sentinel-clj.crap.models
  (:require [clojure.string :as string])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def max-safe-integer 9007199254740991)

(defn contract-error [code message]
  (ex-info message {:error code}))

(defn sha256-bytes [^bytes value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") value)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn sha256-string [value]
  (sha256-bytes (.getBytes ^String value StandardCharsets/UTF_8)))

(defn unicode-scalar-string? [value]
  (and (string? value)
       (loop [index 0]
         (if (= index (count value))
           true
           (let [character (.charAt ^String value index)]
             (cond
               (Character/isHighSurrogate character)
               (and (< (inc index) (count value))
                    (Character/isLowSurrogate (.charAt ^String value (inc index)))
                    (recur (+ index 2)))

               (Character/isLowSurrogate character) false
               :else (recur (inc index))))))))

(defn valid-module-relative-path? [value]
  (let [components (when (string? value) (string/split value #"/" -1))]
    (and (unicode-scalar-string? value)
         (not (empty? value))
         (not (string/starts-with? value "/"))
         (not (string/includes? value "\\"))
         (not (string/includes? value "\u0000"))
         (every? #(not (empty? %)) components)
         (not-any? #{"." ".."} components))))

(defn require-module-relative-path [value]
  (when-not (valid-module-relative-path? value)
    (throw (contract-error :module-relative-path-invalid
                           "moduleRelativePath must be a normalized relative POSIX path")))
  value)

(defn require-safe-integer [value minimum error-code label]
  (when-not (and (integer? value)
                 (<= minimum value max-safe-integer))
    (throw (contract-error error-code (str label " is outside the JSON safe integer range"))))
  value)

(defn parse-unsigned-integer [value error-code label]
  (cond
    (and (integer? value) (not (neg? value))) (bigint value)
    (and (string? value) (re-matches #"(?:0|[1-9][0-9]*)" value)) (bigint value)
    :else (throw (contract-error error-code (str label " must be an unsigned integer")))))
