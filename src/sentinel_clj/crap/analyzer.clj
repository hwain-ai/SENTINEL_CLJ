(ns sentinel-clj.crap.analyzer
  (:require [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [sentinel-clj.crap.models :as models]
            [sentinel-clj.crap.semantic-site :as site])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]))

(def simple-decisions
  #{"if" "if-not" "if-let" "if-some"
    "when" "when-not" "when-let" "when-some" "when-first"
    "and" "or" "loop" "loop*" "catch"})

(def multi-decisions
  #{"cond" "condp" "case" "cond->" "cond->>" "some->" "some->>"})

(def container-heads
  #{"deftype" "defrecord" "reify" "extend-type" "extend-protocol"})

(def fn-heads #{"fn" "fn*"})
(def defn-heads #{"defn" "defn-"})
(def inert-heads #{"quote" "var" "comment" "defmacro" "defprotocol"})
(def binding-heads
  #{"let" "let*" "loop" "loop*" "binding" "with-open"
    "if-let" "if-some" "when-let" "when-some" "when-first"})

(defn- head-name [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn- callable-form? [form]
  (let [head (head-name form)]
    (or (defn-heads head) (fn-heads head) (container-heads head))))

(defn- reader-conditional-anywhere? [form]
  (cond
    (reader-conditional? form) true
    (map? form) (some reader-conditional-anywhere? (mapcat identity form))
    (coll? form) (some reader-conditional-anywhere? form)
    :else false))

(defn- blocked-tag [tag]
  (throw (models/contract-error :analysisReadError
                                (str "tagged literal is unsupported: " tag))))

(defn- read-all-forms [module-relative-path source]
  (let [eof (Object.)]
    (try
      (with-open [input (reader-types/indexing-push-back-reader source 8 module-relative-path)]
        (binding [reader/*read-eval* false
                  reader/*data-readers* {'inst (fn [_] (blocked-tag 'inst))
                                         'uuid (fn [_] (blocked-tag 'uuid))}
                  reader/*default-data-reader-fn* nil]
          (loop [forms []]
            (let [form (reader/read {:eof eof :read-cond :preserve} input)]
              (if (identical? eof form)
                forms
                (recur (conj forms form)))))))
      (catch Exception error
        (if (:error (ex-data error))
          (throw error)
          (throw (ex-info "Clojure source could not be read safely"
                          {:error :analysisReadError}
                          error)))))))

(defn- require-supported-reader-forms [forms]
  (when (some reader-conditional-anywhere? forms)
    (throw (models/contract-error :readerConditionalUnsupported
                                  "reader conditionals are unsupported in strict analysis"))))

(defn- namespace-name [forms]
  (let [names (for [form forms
                    :when (= "ns" (head-name form))
                    :let [candidate (second form)]
                    :when (symbol? candidate)]
                (str candidate))]
    (when-not (= 1 (count names))
      (throw (models/contract-error :namespaceDeclarationInvalid
                                    "source must contain exactly one namespace declaration")))
    (first names)))

(defn- decode-utf8 [^bytes source-bytes]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (.toString (.decode decoder (ByteBuffer/wrap source-bytes)))
      (catch CharacterCodingException error
        (throw (ex-info "source is not valid UTF-8"
                        {:error :sourceEncodingInvalid}
                        error))))))

(defn- strip-byte-order-mark [source]
  (if (and (pos? (count source)) (= \uFEFF (.charAt ^String source 0)))
    {:reader-source (subs source 1) :prefix-bytes 3}
    {:reader-source source :prefix-bytes 0}))

(defn- line-start-indices [source]
  (loop [index 0 starts [0]]
    (if (= index (count source))
      starts
      (let [character (.charAt ^String source index)
            crlf? (and (= character \return)
                       (< (inc index) (count source))
                       (#{\newline \formfeed} (.charAt ^String source (inc index))))]
        (cond
          crlf? (recur (+ index 2) (conj starts (+ index 2)))
          (= character \return) (recur (inc index) (conj starts (inc index)))
          (= character \newline) (recur (inc index) (conj starts (inc index)))
          :else (recur (inc index) starts))))))

(defn- char-index [context line column]
  (let [starts (:line-starts context)
        line-index (dec line)]
    (when-not (and (integer? line) (integer? column)
                   (<= 0 line-index) (< line-index (count starts)) (pos? column))
      (throw (models/contract-error :sourceRangeInvalid "reader returned an invalid line or column")))
    (let [index (+ (nth starts line-index) (dec column))]
      (when (> index (count (:reader-source context)))
        (throw (models/contract-error :sourceRangeInvalid "reader range exceeds source text")))
      index)))

(defn- byte-index [context character-index]
  (+ (:prefix-bytes context)
     (alength (.getBytes (subs (:reader-source context) 0 character-index)
                        StandardCharsets/UTF_8))))

(defn- metadata-range [context form shorthand?]
  (let [metadata (meta form)
        start-character (char-index context (:line metadata) (:column metadata))
        adjusted-start (if shorthand? (dec start-character) start-character)
        end-character (char-index context (:end-line metadata) (:end-column metadata))]
    (when (or (neg? adjusted-start)
              (and shorthand?
                   (not= \# (.charAt ^String (:reader-source context) adjusted-start))))
      (throw (models/contract-error :sourceRangeInvalid "anonymous shorthand range is invalid")))
    {:start-byte (byte-index context adjusted-start)
     :end-byte (byte-index context end-character)
     :start-line (:line metadata)
     :end-line (:end-line metadata)}))

(defn- source-range [context form body]
  (if (meta form)
    (metadata-range context form false)
    (let [body-form (last body)]
      (when-not (meta body-form)
        (throw (models/contract-error :sourceRangeInvalid "callable has no reader source range")))
      (metadata-range context body-form true))))

(defn- arity-signature [parameters]
  (when-not (vector? parameters)
    (throw (models/contract-error :callableSyntaxUnsupported "callable parameters must be a vector")))
  (let [ampersand-index (.indexOf ^java.util.List parameters '&)]
    (if (neg? ampersand-index)
      (str "fixed:" (count parameters))
      (do
        (when-not (= (+ ampersand-index 2) (count parameters))
          (throw (models/contract-error :callableSyntaxUnsupported "variadic parameters are malformed")))
        (str "variadic:" ampersand-index)))))

(defn- callable-clauses [parts whole-form]
  (cond
    (vector? (first parts))
    [{:parameters (first parts) :body (rest parts) :range-form whole-form}]

    (and (seq parts) (every? #(and (seq? %) (vector? (first %))) parts))
    (mapv (fn [clause]
            {:parameters (first clause) :body (rest clause) :range-form clause})
          parts)

    :else
    (throw (models/contract-error :callableSyntaxUnsupported "callable arity clauses are malformed"))))

(defn- strip-defn-prefix [form]
  (loop [parts (drop 2 form)]
    (cond
      (string? (first parts)) (recur (rest parts))
      (map? (first parts)) (rest parts)
      :else parts)))

(defn- strip-fn-prefix [form]
  (let [parts (rest form)]
    (if (symbol? (first parts)) (rest parts) parts)))

(defn- multi-decision-count [head form]
  (let [arguments (rest form)]
    (case head
      "cond" (quot (count arguments) 2)
      "condp" (quot (max 0 (- (count arguments) 2)) 2)
      "case" (quot (inc (max 0 (dec (count arguments)))) 2)
      ("cond->" "cond->>") (quot (max 0 (dec (count arguments))) 2)
      ("some->" "some->>") (max 0 (dec (count arguments)))
      0)))

(declare decision-count)

(defn- collection-decision-count [form]
  (reduce + 0 (map decision-count (if (map? form) (mapcat identity form) form))))

(defn- decision-count [form]
  (let [head (head-name form)]
    (cond
      (inert-heads head) 0
      (callable-form? form) 0
      (simple-decisions head) (+ 1 (collection-decision-count (rest form)))
      (multi-decisions head) (+ (multi-decision-count head form)
                                (collection-decision-count (rest form)))
      (coll? form) (collection-decision-count form)
      :else 0)))

(defn- complexity [body]
  (inc (reduce + 0 (map decision-count body))))

(defn- named-base [context kind callable-name container arity]
  {:version "clj-semantic-site-v1"
   :module (:module-relative-path context)
   :namespace (:namespace context)
   :kind kind
   :container container
   :name callable-name
   :arity arity})

(defn- anonymous-base [context owner role arity]
  {:version "clj-semantic-site-v1"
   :module (:module-relative-path context)
   :namespace (:namespace context)
   :kind :fn
   :owner owner
   :role role
   :arity arity})

(defn- qualified-name [context callable-name container]
  (if container
    (str (:namespace context) "/" container "." callable-name)
    (str (:namespace context) "/" callable-name)))

(declare scan-form)

(defn- nested-role [parent-role category value]
  [parent-role category value])

(defn- scan-map [context owner role form]
  (mapcat (fn [[key value]]
            (concat (scan-form context owner (nested-role role :map-key nil) key)
                    (scan-form context owner (nested-role role :map-value (site/semantic-digest key)) value)))
          (sort-by (comp pr-str site/canonical-value key) form)))

(defn- scan-binding-values [context owner role bindings]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (throw (models/contract-error :callableSyntaxUnsupported "binding vector is malformed")))
  (mapcat (fn [[pattern value]]
            (scan-form context owner
                       (nested-role role :binding (site/canonical-value pattern))
                       value))
          (partition 2 bindings)))

(defn- scan-binding-form [context owner role form]
  (concat (scan-binding-values context owner role (second form))
          (mapcat (fn [body-form]
                    (scan-form context owner
                               (nested-role role :body-value nil)
                               body-form))
                  (drop 2 form))))

(defn- scan-def-form [context owner role form]
  (if (< (count form) 3)
    []
    (scan-form context owner
               (nested-role role :var (site/canonical-value (second form)))
               (nth form 2))))

(defn- scan-generic-list [context owner role form]
  (let [callee (first form)
        callee-anchor (if (or (symbol? callee) (keyword? callee))
                        (site/canonical-value callee)
                        :dynamic-callee)
        callee-role (nested-role role :callee nil)
        argument-role (nested-role role :call-argument callee-anchor)]
    (concat (scan-form context owner callee-role callee)
            (mapcat #(scan-form context owner argument-role %) (rest form)))))

(defn- scan-list-children [context owner role form]
  (let [head (head-name form)]
    (cond
      (= "def" head) (scan-def-form context owner role form)
      (binding-heads head) (scan-binding-form context owner role form)
      :else (scan-generic-list context owner role form))))

(defn- scan-children [context owner role form]
  (cond
    (map? form) (scan-map context owner role form)
    (list? form) (scan-list-children context owner role form)
    (coll? form) (mapcat (fn [child]
                           (scan-form context owner
                                      (nested-role role :collection-value nil)
                                      child))
                         form)
    :else []))

(defn- callable-candidate [context kind callable-name container owner role clause]
  (let [arity (arity-signature (:parameters clause))
        semantic-hash (site/semantic-digest [(:parameters clause) (:body clause)])
        base (if callable-name
               (named-base context kind callable-name container arity)
               (anonymous-base context owner role arity))]
    {:arity-signature arity
     :cyclomatic-complexity (complexity (:body clause))
     :identity-base base
     :identity-collision-mode (if (or (nil? callable-name)
                                      (and container (string/starts-with? container "reify@")))
                                :ambiguous
                                :semantic-discriminator)
     :kind kind
     :qualified-name (qualified-name context (or callable-name "<anonymous>") container)
     :semantic-hash semantic-hash
     :source-digest (:source-digest context)
     :source-byte-length (:source-byte-length context)
     :source-line-count (:source-line-count context)
     :module-relative-path (:module-relative-path context)
     :source-range (source-range context (:range-form clause) (:body clause))
     :body (:body clause)}))

(defn- scan-callable-clauses [context kind callable-name container owner role clauses]
  (mapcat (fn [clause]
            (let [candidate (callable-candidate context kind callable-name container owner role clause)
                  child-owner (site/semantic-digest (:identity-base candidate))
                  child-role [:callable-body child-owner]]
              (cons candidate
                    (mapcat (fn [body-form]
                              (scan-form context child-owner child-role body-form))
                            (:body candidate)))))
          clauses))

(defn- scan-defn [context owner role form]
  (let [callable-name (second form)]
    (when-not (symbol? callable-name)
      (throw (models/contract-error :callableSyntaxUnsupported "defn name must be a symbol")))
    (scan-callable-clauses context
                           (if (= "defn-" (head-name form)) :defn- :defn)
                           (str callable-name)
                           nil
                           owner
                           role
                           (callable-clauses (strip-defn-prefix form) form))))

(defn- scan-fn [context owner role form]
  (scan-callable-clauses context
                         :fn
                         nil
                         nil
                         owner
                         role
                         (callable-clauses (strip-fn-prefix form) form)))

(defn- method-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (or (vector? (second form))
           (and (seq? (second form)) (vector? (first (second form)))))))

(defn- type-method-contexts [form]
  (let [container (str (second form))]
    (for [method (drop 3 form) :when (method-form? method)] [container method])))

(defn- reify-method-contexts [form]
  (for [method (rest form) :when (method-form? method)] ["reify" method]))

(defn- extend-type-method-contexts [form]
  (loop [items (drop 2 form) current-protocol nil result []]
    (if (empty? items)
      result
      (let [item (first items)]
        (if (symbol? item)
          (recur (rest items) (str item) result)
          (recur (rest items) current-protocol
                 (if (method-form? item)
                   (conj result [(str (second form) "/" current-protocol) item])
                   result)))))))

(defn- extend-protocol-method-contexts [form]
  (loop [items (drop 2 form) current-type nil result []]
    (if (empty? items)
      result
      (let [item (first items)]
        (if (symbol? item)
          (recur (rest items) (str item) result)
          (recur (rest items) current-type
                 (if (method-form? item)
                   (conj result [(str (second form) "/" current-type) item])
                   result)))))))

(defn- method-contexts [form]
  (case (head-name form)
    ("deftype" "defrecord") (type-method-contexts form)
    "reify" (reify-method-contexts form)
    "extend-type" (extend-type-method-contexts form)
    "extend-protocol" (extend-protocol-method-contexts form)
    []))

(defn- scan-method [context owner role container method]
  (let [method-name (str (first method))
        clauses (callable-clauses (rest method) method)]
    (scan-callable-clauses context :method method-name container owner role clauses)))

(defn- scan-container [context owner role form]
  (mapcat (fn [[container method]]
            (let [owned-container (if (= "reify" container)
                                    (str "reify@" (site/semantic-digest role))
                                    container)]
              (scan-method context owner
                         (nested-role role :method [owned-container (str (first method))])
                         owned-container
                         method)))
          (method-contexts form)))

(defn- scan-form [context owner role form]
  (let [head (head-name form)]
    (cond
      (defn-heads head) (scan-defn context owner role form)
      (fn-heads head) (scan-fn context owner role form)
      (container-heads head) (scan-container context owner role form)
      (inert-heads head) []
      :else (scan-children context owner role form))))

(defn- duplicate-semantic-hash? [candidates]
  (some #(> (count %) 1) (vals (group-by :semantic-hash candidates))))

(defn- identity-descriptor [candidate group-size]
  (if (= group-size 1)
    (:identity-base candidate)
    (assoc (:identity-base candidate) :declaration-hash (:semantic-hash candidate))))

(defn- resolve-identities [candidates]
  (let [groups (group-by :identity-base candidates)]
    (doseq [[_ group] groups]
      (when (> (count group) 1)
        (when (or (some #(= :ambiguous (:identity-collision-mode %)) group)
                  (duplicate-semantic-hash? group))
          (throw (models/contract-error :identityAmbiguous
                                        "semantic callable descriptor is duplicated")))))
    (->> groups
         (mapcat (fn [[_ group]]
                   (map #(assoc % :callable-id (site/callable-id
                                                 (identity-descriptor % (count group))))
                        group)))
         (map #(dissoc % :identity-base :identity-collision-mode :semantic-hash :body))
         (sort-by #(get-in % [:source-range :start-byte]))
         vec)))

(defn analyze-bytes [module-relative-path ^bytes source-bytes]
  (models/require-module-relative-path module-relative-path)
  (let [decoded (decode-utf8 source-bytes)]
    (when-not (models/unicode-scalar-string? decoded)
      (throw (models/contract-error :sourceEncodingInvalid
                                    "source contains an unpaired UTF-16 surrogate")))
    (let [{:keys [reader-source prefix-bytes]} (strip-byte-order-mark decoded)
          forms (read-all-forms module-relative-path reader-source)]
      (require-supported-reader-forms forms)
      (let [line-starts (line-start-indices reader-source)
            context {:module-relative-path module-relative-path
                     :namespace (namespace-name forms)
                     :reader-source reader-source
                     :prefix-bytes prefix-bytes
                     :line-starts line-starts
                     :source-byte-length (alength source-bytes)
                     :source-line-count (count line-starts)
                     :source-digest (models/sha256-bytes source-bytes)}
            candidates (mapcat #(scan-form context
                                           "module-root"
                                           [:module-root]
                                           %)
                               forms)]
        (resolve-identities candidates)))))

(defn analyze-source [module-relative-path source]
  (when-not (models/unicode-scalar-string? source)
    (throw (models/contract-error :sourceEncodingInvalid
                                  "source must contain Unicode scalar values")))
  (analyze-bytes module-relative-path (.getBytes ^String source StandardCharsets/UTF_8)))
