(ns sentinel-clj.config
  (:require [clojure.string :as string]
            [sentinel-clj.crap.models :as models]
            [sentinel-clj.json :as contract-json])
  (:import [java.nio.file FileSystems Files LinkOption Path]))

(def root-keys #{:specVersion :modules})
(def module-keys
  #{:id :language :root :production :testCommand :testRoots :testPatterns :coverage :mutation})
(def coverage-keys #{:format :report :command})
(def mutation-keys #{:format :report :command})

(defn- fail [code message]
  (throw (models/contract-error code message)))

(defn- exact-map? [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- string-vector? [value]
  (and (vector? value) (seq value) (every? #(and (string? %) (not (string/blank? %))) value)))

(defn- valid-tool [tool expected-keys format]
  (and (exact-map? tool expected-keys)
       (= format (:format tool))
       (and (string? (:report tool)) (not (string/blank? (:report tool))))
       (string-vector? (:command tool))))

(defn- valid-module? [module]
  (and (exact-map? module module-keys)
       (and (string? (:id module)) (not (string/blank? (:id module))))
       (= "clojure" (:language module))
       (and (string? (:root module)) (not (string/blank? (:root module))))
       (string-vector? (:production module))
       (string-vector? (:testCommand module))
       (string-vector? (:testRoots module))
       (string-vector? (:testPatterns module))
       (valid-tool (:coverage module) coverage-keys "sentinel-cloverage-form-v1")
       (valid-tool (:mutation module) mutation-keys "sentinel-clj-mutate-report-v1")))

(defn- require-config [config]
  (when-not (and (exact-map? config root-keys)
                 (= "1.0.0" (:specVersion config))
                 (vector? (:modules config))
                 (seq (:modules config))
                 (every? valid-module? (:modules config)))
    (fail :projectConfigShapeInvalid "sentinel.config.json has an invalid shape"))
  (let [ids (map :id (:modules config))]
    (when-not (= (count ids) (count (set ids)))
      (fail :projectConfigShapeInvalid "module IDs must be unique")))
  config)

(defn- real-project [project]
  (let [path (.toAbsolutePath (.normalize (Path/of project (make-array String 0))))]
    (when-not (and (Files/isDirectory path (make-array LinkOption 0))
                   (not (Files/isSymbolicLink path)))
      (fail :projectInvalid "project must be a real directory"))
    (.toRealPath path (make-array LinkOption 0))))

(defn- inside [^Path parent relative code]
  (when-not (and (string? relative) (not (string/includes? relative "\u0000")))
    (fail code "configured path is invalid"))
  (let [path (.normalize (.resolve parent relative))]
    (when-not (.startsWith path parent)
      (fail code "configured path leaves the module"))
    path))

(defn- select-module [config module-id]
  (let [modules (:modules config)
        matches (if module-id (filter #(= module-id (:id %)) modules) modules)]
    (when-not (= 1 (count matches))
      (fail :moduleSelectionInvalid "select exactly one Clojure module"))
    (first matches)))

(defn- matchers [patterns]
  (mapv #(.getPathMatcher (FileSystems/getDefault) (str "glob:" %)) patterns))

(defn- production-file? [^Path root matchers ^Path candidate]
  (let [relative (.relativize root candidate)]
    (and (Files/isRegularFile candidate (make-array LinkOption 0))
         (not (Files/isSymbolicLink candidate))
         (some #(.matches % relative) matchers))))

(defn- utf8-key [^Path root ^Path path]
  (.getBytes (str (.relativize root path)) "UTF-8"))

(defn- production-files [^Path module-root patterns]
  (let [matchers (matchers patterns)
        files (with-open [paths (Files/walk module-root (make-array java.nio.file.FileVisitOption 0))]
                (->> (.iterator paths)
                     iterator-seq
                     (filter #(production-file? module-root matchers %))
                     (sort-by #(vec (utf8-key module-root %)))
                     vec))]
    (when (empty? files)
      (fail :productionInventoryEmpty "configured production inventory is empty"))
    files))

(defn load-project [project config-path module-id]
  (let [project-root (real-project project)
        config-file (inside project-root (or config-path "sentinel.config.json") :configPathInvalid)
        config (require-config (contract-json/read-path config-file))
        module (select-module config module-id)
        module-root (inside project-root (:root module) :moduleRootInvalid)]
    (when-not (and (Files/isDirectory module-root (make-array LinkOption 0))
                   (not (Files/isSymbolicLink module-root)))
      (fail :moduleRootInvalid "module root must be a real directory"))
    {:project-root project-root
     :config-file config-file
     :module-root module-root
     :module module
     :production-files (production-files module-root (:production module))}))

(defn resolve-report [project tool-key]
  (inside (:module-root project)
          (get-in project [:module tool-key :report])
          :reportPathInvalid))
