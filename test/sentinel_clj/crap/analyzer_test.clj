(ns sentinel-clj.crap.analyzer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as tools-reader]
            [sentinel-clj.crap.analyzer :as analyzer]))

(def inventory-source
  "(ns sample.core)\n
   (defprotocol P (run [this x]))\n
   (defn public\n
     ([x] (if x x 0))\n
     ([x y] (and x y)))\n
   (defn- hidden [x]\n
     (when x\n
       (fn [y] (or y x))))\n
   (deftype Box [] P\n
     (run [this x] (if x x 0))\n
     Object\n
     (toString [this] \"box\"))\n
   (defrecord RecordBox [] P\n
     (run [this x] x))\n
   (def built (reify P\n
                (run [this x] (case x 1 :one :other))))\n
   (extend-type java.lang.String P\n
     (run [this x] x))\n
   (extend-protocol P\n
     java.lang.Long\n
     (run [this x] x))\n
   (def handler (fn named [x]\n
                  (cond x :yes :else :no)))\n
   #(+ % 1)\n")

(defn- analyze [source]
  (analyzer/analyze-source "src/sample/core.clj" source))

(defn- error-code [thunk]
  (:error (ex-data (try (thunk) (catch Exception error error)))))

(deftest inventories-named-multi-arity-method-and-anonymous-callables
  (let [rows (analyze inventory-source)
        kinds (frequencies (map :kind rows))
        public (filter #(= "sample.core/public" (:qualified-name %)) rows)
        hidden (first (filter #(= "sample.core/hidden" (:qualified-name %)) rows))]
    (is (= 2 (count public)))
    (is (= #{"fixed:1" "fixed:2"} (set (map :arity-signature public))))
    (is (= #{2} (set (map :cyclomatic-complexity public))))
    (is (= 2 (:cyclomatic-complexity hidden)))
    (is (= 2 (:defn kinds)))
    (is (= 1 (:defn- kinds)))
    (is (= 6 (:method kinds)))
    (is (= 3 (:fn kinds)))
    (is (= (count rows) (count (set (map :callable-id rows)))))))

(deftest decision-matrix-counts-syntax-exactly-and-excludes-child-functions
  (let [source "(ns decisions)\n
                (defn matrix [x]\n
                  (if x 1 0)\n
                  (if-not x 1 0)\n
                  (if-let [v x] v 0)\n
                  (if-some [v x] v 0)\n
                  (when x 1)\n
                  (when-not x 1)\n
                  (when-let [v x] v)\n
                  (when-some [v x] v)\n
                  (when-first [v x] v)\n
                  (and x true)\n
                  (or x false)\n
                  (loop [v x] v)\n
                  (try x (catch Exception e nil))\n
                  (cond x 1 :else 0)\n
                  (condp = x 1 :one 2 :two)\n
                  (case x 1 :one 2 :two :other)\n
                  (cond-> x true inc false dec)\n
                  (some-> x inc dec)\n
                  (fn [y] (if y 1 0))\n
                  x)"
        rows (analyzer/analyze-source "src/decisions.clj" source)
        parent (first (filter #(= "decisions/matrix" (:qualified-name %)) rows))
        child (first (filter #(= :fn (:kind %)) rows))]
    (is (= 25 (:cyclomatic-complexity parent)))
    (is (= 2 (:cyclomatic-complexity child)))))

(deftest semantic-identities-ignore-position-and-comment-changes
  (let [compact "(ns stable)\n(def h (fn [x] (if x 1 0)))\n"
        shifted "\n; moved without semantic change\n(ns stable)\n\n(def h (fn [x] (if x 1 0)))\n"]
    (is (= (mapv :callable-id (analyze compact))
           (mapv :callable-id (analyze shifted))))
    (is (not= (mapv #(get-in % [:source-range :start-byte]) (analyze compact))
              (mapv #(get-in % [:source-range :start-byte]) (analyze shifted))))))

(deftest binding-owned-anonymous-identities-ignore-unrelated-siblings-and-reordering
  (let [first-source "(ns binding)\n
                      (defn outer []\n
                        (let [first (fn [x] x)\n
                              unused 10\n
                              second (fn [x] x)]\n
                          [first second]))\n"
        reordered-source "(ns binding)\n
                          (defn outer []\n
                            (let [second (fn [x] x)\n
                                  unused 99\n
                                  first (fn [x] x)]\n
                              [first second]))\n"
        anonymous-ids (fn [source]
                        (->> (analyzer/analyze-source "src/binding.clj" source)
                             (filter #(= :fn (:kind %)))
                             (map :callable-id)
                             set))]
    (is (= 2 (count (anonymous-ids first-source))))
    (is (= (anonymous-ids first-source) (anonymous-ids reordered-source)))))

(deftest anonymous-identities-ignore-body-and-nonsemantic-sibling-changes
  (let [original "(ns anonymous-stable)\n
                  (defn outer []\n
                    (let [handler (fn [x] (inc x))]\n
                      (register (fn [event] (:old event)))\n
                      [(fn [item] (:old item))]))\n"
        changed "(ns anonymous-stable)\n
                 (defn outer []\n
                   (let [handler (fn [x] (dec x))]\n
                     (register :unrelated (fn [event] (:new event)))\n
                     [:unrelated (fn [item] (:new item))]))\n"
        anonymous-ids (fn [source]
                        (->> (analyzer/analyze-source "src/anonymous_stable.clj" source)
                             (filter #(= :fn (:kind %)))
                             (map :callable-id)
                             set))]
    (is (= 3 (count (anonymous-ids original))))
    (is (= (anonymous-ids original) (anonymous-ids changed)))))

(deftest exact-semantic-duplicate-is-ambiguous-without-an-invented-ordinal
  (is (= :identityAmbiguous
         (error-code #(analyze "(ns duplicate)\n[(fn [x] x) (fn [x] x)]\n"))))
  (is (= :identityAmbiguous
         (error-code #(analyze "(ns duplicate)\n[(fn [x] x) (fn [x] (inc x))]\n"))))
  (is (= 2
         (count (analyze "(ns distinct)\n
                          (let [left (fn [x] x)
                                right (fn [x] (inc x))]
                            [left right])\n")))))

(deftest unowned-reify-receivers-do-not-use-method-bodies-as-an-ordinal
  (is (= :identityAmbiguous
         (error-code
          #(analyze "(ns duplicate-reify)\n
                     [(reify Runnable (run [this] :left))
                      (reify Runnable (run [this] :right))]\n")))))

(deftest same-name-redefinitions-use-semantics-only-when-disambiguation-is-needed
  (let [different (analyzer/analyze-source
                    "src/redefined.clj"
                    "(ns redefined)\n(defn value [x] x)\n(defn value [x] (inc x))\n")]
    (is (= 2 (count different)))
    (is (= 2 (count (set (map :callable-id different))))))
  (is (= :identityAmbiguous
         (error-code #(analyzer/analyze-source
                        "src/redefined.clj"
                        "(ns redefined)\n(defn value [x] x)\n(defn value [x] x)\n")))))

(deftest shorthand-and-semantic-owner-identities-are-position-free
  (let [source "(ns shorthand)\n(def a #(+ % 1))\n(def b #(+ % 1))\n"
        shifted "\n(ns shorthand)\n\n(def a #(+ % 1))\n\n(def b #(+ % 1))\n"]
    (is (= (mapv :callable-id (analyzer/analyze-source "src/shorthand.clj" source))
           (mapv :callable-id (analyzer/analyze-source "src/shorthand.clj" shifted))))
    (is (= 2 (count (analyzer/analyze-source "src/shorthand.clj" source)))))
  (let [source "(ns owned)\n
                (def left (reify Runnable (run [this] :same)))\n
                (def right (reify Runnable (run [this] :same)))\n"
        rows (analyzer/analyze-source "src/owned.clj" source)]
    (is (= 2 (count rows)))
    (is (= 2 (count (set (map :callable-id rows)))))))

(deftest quoted-and-macro-definition-data-is-not-runtime-callable-or-complexity
  (let [source "(ns inert)\n
                (defmacro build [] '(fn [x] (if x 1 0)))\n
                (defn runtime []\n
                  '(fn [x] (if x 1 0))\n
                  (comment (fn [y] (when y y)))\n
                  :done)\n"
        rows (analyzer/analyze-source "src/inert.clj" source)]
    (is (= 1 (count rows)))
    (is (= :defn (:kind (first rows))))
    (is (= 1 (:cyclomatic-complexity (first rows))))))

(deftest reader-never-evaluates-loads-or-expands-project-code
  (testing "namespace dependencies and macro bodies remain inert data"
    (is (= 1 (count (analyze "(ns safe (:require [project.does-not-exist]))\n
                              (defmacro dangerous [] (spit \"/tmp/never\" \"bad\"))\n
                              (defn ok [] (dangerous))")))))
  (testing "reader eval is rejected before its expression runs"
    (let [marker (.toFile (java.nio.file.Files/createTempFile "sentinel-clj-reader-eval" ".marker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))]
      (.delete marker)
      (is (= :analysisReadError
             (error-code #(analyze (str "#=(spit " (pr-str (.getPath marker)) " \"ran\")")))))
      (is (false? (.exists marker)))))
  (testing "custom and built-in tagged literals are not invoked"
    (is (= :analysisReadError (error-code #(analyze "#project/evil {:x 1}"))))
    (is (= :analysisReadError (error-code #(analyze "#inst \"2026-01-01\"")))))
  (testing "caller and project data reader bindings cannot replace the sealed reader"
    (let [called? (atom false)
          malicious (fn [_] (reset! called? true))]
      (binding [clojure.core/*data-readers* {'project/evil malicious}
                tools-reader/*data-readers* {'project/evil malicious}
                tools-reader/*default-data-reader-fn* (fn [_ _] (reset! called? true))]
        (is (= :analysisReadError (error-code #(analyze "#project/evil {:x 1}")))))
      (is (false? @called?))))
  (testing "reader conditionals are rejected instead of selecting a branch"
    (is (= :readerConditionalUnsupported
           (error-code #(analyze "#?(:clj (defn chosen [] 1) :cljs (defn other [] 2))"))))))

(deftest source-ranges-use-original-utf8-bytes-with-bom-and-crlf
  (let [source (str "\uFEFF(ns 위치.core)\r\n"
                    "\"𐀀\" (defn 함수 [x] x)\r\n")
        bytes (.getBytes source java.nio.charset.StandardCharsets/UTF_8)
        row (first (analyzer/analyze-bytes "src/위치/core.clj" bytes))
        expected (alength (.getBytes (subs source 0 (.indexOf source "(defn"))
                                     java.nio.charset.StandardCharsets/UTF_8))
        closing-index (inc (.lastIndexOf source ")\r\n"))
        expected-end (alength (.getBytes (subs source 0 closing-index)
                                         java.nio.charset.StandardCharsets/UTF_8))]
    (is (= expected (get-in row [:source-range :start-byte])))
    (is (= expected-end (get-in row [:source-range :end-byte])))
    (is (= 2 (get-in row [:source-range :start-line]))))
  (is (= :sourceEncodingInvalid
         (error-code #(analyzer/analyze-bytes "src/a.clj"
                                              (byte-array [(unchecked-byte 0xC3)
                                                           (unchecked-byte 0x28)])))))
  (is (= :module-relative-path-invalid
         (error-code #(analyzer/analyze-source "../escape.clj" "(defn x [] 1)")))))

(deftest product-callables-stay-at-or-below-eight-complexity
  (let [source-files (filter #(and (.isFile %)
                                   (.endsWith (.getName %) ".clj"))
                             (file-seq (io/file "src")))
        rows (mapcat (fn [file]
                       (analyzer/analyze-bytes
                         (.replace (.getPath file) java.io.File/separator "/")
                         (java.nio.file.Files/readAllBytes (.toPath file))))
                     source-files)]
    (is (seq rows))
    (is (every? #(<= (:cyclomatic-complexity %) 8) rows)
        (pr-str (map #(select-keys % [:qualified-name :cyclomatic-complexity])
                     (filter #(< 8 (:cyclomatic-complexity %)) rows))))))
