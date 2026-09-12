(ns sentinel-clj.mutation.backend-lock
  (:import [java.io IOException]
           [java.nio.file Files LinkOption Path]
           [java.util.concurrent TimeUnit]))

(def install-root-property "sentinel.clj.install-root")

(defn- dependency-error []
  (ex-info "vendored mutation backend verification failed"
           {:error :dependencyError :exit-code 5}))

(defn- install-root []
  (try
    (let [configured (or (System/getProperty install-root-property)
                         (System/getProperty "user.dir"))
          root (.toAbsolutePath (.normalize (Path/of configured (make-array String 0))))]
      (when-not (and (Files/isDirectory root (make-array LinkOption 0))
                     (not (Files/isSymbolicLink root)))
        (throw (dependency-error)))
      root)
    (catch java.nio.file.InvalidPathException _
      (throw (dependency-error)))))

(defn- start-verifier [^Path root]
  (let [script (.resolve root "scripts/backend_lock.py")
        lock (.resolve root "backend.lock.json")
        backend (.resolve root "third_party/clj-mutate")
        command ["/usr/bin/python3" "-I" (str script) (str lock) (str backend)]
        builder (ProcessBuilder. ^java.util.List command)
        environment (.environment builder)]
    (.clear environment)
    (.put environment "PATH" "/usr/bin:/bin")
    (.put environment "LANG" "C.UTF-8")
    (.put environment "LC_ALL" "C.UTF-8")
    (.directory builder (.toFile root))
    (.redirectOutput builder java.lang.ProcessBuilder$Redirect/DISCARD)
    (.redirectError builder java.lang.ProcessBuilder$Redirect/DISCARD)
    (try
      (.start builder)
      (catch IOException _
        (throw (dependency-error))))))

(defn verify! []
  (let [process (start-verifier (install-root))]
    (when-not (.waitFor process 30 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (dependency-error)))
    (when-not (zero? (.exitValue process))
      (throw (dependency-error)))))
