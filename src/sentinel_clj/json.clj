(ns sentinel-clj.json
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [sentinel-clj.crap.models :as models])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.util Arrays]))

(defn- decode-utf8 [^bytes content]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (.toString (.decode decoder (ByteBuffer/wrap content)))
      (catch Exception error
        (throw (ex-info "JSON is not valid UTF-8" {:error :jsonEncodingInvalid} error))))))

(defn read-path [^Path path]
  (try
    (json/read-str (decode-utf8 (Files/readAllBytes path)) :key-fn keyword)
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception error
      (throw (ex-info "JSON could not be parsed" {:error :jsonInvalid} error)))))

(defn write-bytes [value]
  (.getBytes ^String (json/write-str value) StandardCharsets/UTF_8))

(defn- key-name [key]
  (cond
    (keyword? key) (name key)
    (string? key) key
    :else (throw (ex-info "canonical JSON object key is invalid"
                          {:error :jsonCanonicalInvalid}))))

(defn- append-control [^StringBuilder builder character]
  (.append builder (format "\\u%04x" (int character))))

(defn- append-character [^StringBuilder builder ^String value index]
  (let [character (.charAt value index)]
    (cond
      (= character \u0022) (.append builder "\\\"")
      (= character \u005c) (.append builder "\\\\")
      (<= (int character) 31) (append-control builder character)
      :else (.append builder character))))

(defn- canonical-string [value]
  (when-not (models/unicode-scalar-string? value)
    (throw (ex-info "canonical JSON string contains an invalid scalar"
                    {:error :jsonCanonicalInvalid})))
  (let [builder (StringBuilder. "\"")]
    (loop [index 0]
      (if (= index (count value))
        (str (.append builder \"))
        (let [character (.charAt ^String value index)
              width (if (Character/isHighSurrogate character) 2 1)]
          (append-character builder value index)
          (when (= width 2)
            (append-character builder value (inc index)))
          (recur (+ index width)))))))

(defn- utf8-compare [left right]
  (Arrays/compareUnsigned (.getBytes ^String left StandardCharsets/UTF_8)
                          (.getBytes ^String right StandardCharsets/UTF_8)))

(defn- canonical-scalar [value]
  (cond
    (nil? value) "null"
    (true? value) "true"
    (false? value) "false"
    (string? value) (canonical-string value)
    (and (integer? value) (<= 0 value models/max-safe-integer)) (str value)
    :else (throw (ex-info "canonical JSON value is invalid"
                          {:error :jsonCanonicalInvalid}))))

(declare canonical-value)

(defn- canonical-map [value]
  (let [entries (mapv (fn [[key item]] [(key-name key) item]) value)
        names (mapv first entries)]
    (when-not (= (count names) (count (set names)))
      (throw (ex-info "canonical JSON object keys collide"
                      {:error :jsonCanonicalInvalid})))
    (str "{"
         (string/join ","
                      (map (fn [[key item]]
                             (str (canonical-string key) ":" (canonical-value item)))
                           (sort-by first utf8-compare entries)))
         "}")))

(defn- canonical-vector [value]
  (str "[" (string/join "," (map canonical-value value)) "]"))

(defn canonical-value [value]
  (cond
    (map? value) (canonical-map value)
    (vector? value) (canonical-vector value)
    :else (canonical-scalar value)))

(defn write-canonical-bytes [value]
  (.getBytes ^String (str (canonical-value value) "\n") StandardCharsets/UTF_8))

(defn write-canonical-value-bytes [value]
  (.getBytes ^String (canonical-value value) StandardCharsets/UTF_8))

(defn read-canonical-path [^Path path]
  (let [content (Files/readAllBytes path)
        value (read-path path)]
    (when-not (MessageDigest/isEqual content (write-canonical-bytes value))
      (throw (ex-info "JSON is not canonical" {:error :jsonCanonicalInvalid})))
    value))
