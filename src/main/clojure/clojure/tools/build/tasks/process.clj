;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.process
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.deps :as deps]
    [clojure.tools.build.api :as api]
    [clojure.string :as str])
  (:import
    [java.io InputStream StringWriter File]
    [java.lang ProcessBuilder ProcessBuilder$Redirect]
    [java.util List]))

(defn- copy-stream
  [^InputStream input-stream]
  (let [writer (StringWriter.)]
    (jio/copy input-stream writer)
    (let [s (.toString writer)]
      (when-not (zero? (.length s))
        s))))

(defn process
  "Exec the command made from command-args, redirect out and err as directed,
  and return {:exit exit-code, :out captured-out, :err captured-err}

  Options:
    :command-args - required, coll of string args
    :dir - directory to run the command from, default current directory
    :out - one of :inherit :capture :write :append :ignore
    :err - one of :inherit :capture :write :append :ignore
    :out-file - file path to write if :out is :write or :append
    :err-file - file path to write if :err is :write or :append
    :env - map of environment variables to set

  The :out and :err input flags take one of the following options:
    :inherit - inherit the stream and write the subprocess io to this process's stream (default)
    :capture - capture the stream to a string and return it
    :write - write to :out-file or :err-file
    :append - append to :out-file or :err-file
    :ignore - ignore the stream"
  [{:keys [command-args dir env out err out-file err-file]
    :or {dir ".", out :inherit, err :inherit} :as opts}]
  (println "command args")
  (println command-args)
  (prn command-args)
  (when (not (seq command-args))
    (throw (ex-info "process missing required arg :command-args" opts)))
  (let [pb (ProcessBuilder. ^List command-args)]
    (.directory pb (api/resolve-path (or dir ".")))
    (case out
      :inherit (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
      :write (.redirectOutput pb (ProcessBuilder$Redirect/to (jio/file (api/resolve-path out-file))))
      :append (.redirectOutput pb (ProcessBuilder$Redirect/appendTo (jio/file (api/resolve-path out-file))))
      :capture (.redirectOutput pb ProcessBuilder$Redirect/PIPE)
      :ignore (.redirectOutput pb ProcessBuilder$Redirect/PIPE))
    (case err
      :inherit (.redirectError pb ProcessBuilder$Redirect/INHERIT)
      :write (.redirectError pb (ProcessBuilder$Redirect/to (jio/file (api/resolve-path err-file))))
      :append (.redirectError pb (ProcessBuilder$Redirect/appendTo (jio/file (api/resolve-path err-file))))
      :capture (.redirectError pb ProcessBuilder$Redirect/PIPE)
      :ignore (.redirectError pb ProcessBuilder$Redirect/PIPE))
    (when env
      (let [pb-env (.environment pb)]
        (run! (fn [[k v]] (.put pb-env k v)) env)))
    (let [proc (.start pb)
          exit (.waitFor proc)
          out-str (when (= out :capture) (copy-stream (.getInputStream proc)))
          err-str (when (= err :capture) (copy-stream (.getErrorStream proc)))]
      (cond-> {:exit exit}
        out-str (assoc :out out-str)
        err-str (assoc :err err-str)))))

(defn- need-cp-file
  [os-name java-version command-length]
  (and
    ;; this is only an issue on Windows
    (str/starts-with? os-name "Win")
    ;; CLI support only exists in Java 9+, for Java <= 1.8, the version number is 1.x
    (not (str/starts-with? java-version "1."))
    ;; the actual limit on windows is 8191 (<8k), but giving some room
    (> command-length 8000)))

(defn- make-java-args
  [java-cmd java-opts cp main main-args use-cp-file]
  (let [full-args (vec (concat [java-cmd] java-opts ["-cp" cp (name main)] main-args))
        arg-str (str/join " " full-args)]
    (if (or (= use-cp-file :always)
          (and (= use-cp-file :auto)
            (need-cp-file (System/getProperty "os.name") (System/getProperty "java.version") (count arg-str))))
      (let [cp-file (doto (File/createTempFile "tbuild-" ".cp") (.deleteOnExit))]
        (spit cp-file cp)
        (vec (concat [java-cmd] java-opts ["-cp" (str "@" (.getAbsolutePath cp-file)) (name main)] main-args)))
      full-args)))

(defn which
  "Given the name of an executable, return either a full path to
   its location on the system PATH or nil if not found"
  [cmd]
  (when-let [path (System/getenv "PATH")]
    (let [paths (str/split path (re-pattern File/pathSeparator))]
      (loop [paths paths]
        (when-first [p paths]
          (let [f (jio/file p cmd)]
            (if (and (.isFile f) (.canExecute f))
              (.getCanonicalPath f)
              (recur (rest paths)))))))))

(defn- windows?
  []
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- java-exe
  []
  (if (windows?) "java.exe" "java"))

(defn- java-home-bin
  "Returns path $JAVA_HOME/bin/java if JAVA_HOME set, or nil"
  []
  (when-let [jhome (System/getenv "JAVA_HOME")]
    (let [exe (jio/file jhome "bin" (java-exe))]
      (when (and (.exists exe) (.canExecute exe))
        (.getCanonicalPath exe)))))

(defn java-executable
  "Given the environment, emulate the Clojure CLI logic to determine the
   Java executable path and return it by trying in order:
     $JAVA_CMD
     java on the PATH
     $JAVA_HOME/bin/java"
  []
  (or
   (System/getenv "JAVA_CMD")
   (which (java-exe))
   (java-home-bin)
   (throw (ex-info "Couldn't find java executable via $JAVA_CMD, $PATH, or $JAVA_HOME" {}))))

(defn java-command
  "Create Java command line args. The classpath will be the combination of
  :cp followed by the classpath from the basis, both are optional.

  Options:
    :java-cmd - Java command, default = $JAVA_CMD or 'java' on $PATH, or $JAVA_HOME/bin/java
    :cp - coll of string classpath entries, used first (if provided)
    :basis - runtime basis used for classpath, used last (if provided)
    :java-opts - coll of string jvm opts
    :main - required, main class symbol
    :main-args - coll of main class args
    :use-cp-file - one of:
                     :auto (default) - use only if os=windows && Java >= 9 && command length >= 8k
                     :always - always write classpath to temp file and include
                     :never - never write classpath to temp file (pass on command line)

  Returns:
    :command-args - coll of command arg strings"
  [{:keys [java-cmd cp basis java-opts main main-args use-cp-file]
    :or {use-cp-file :auto} :as _params}]
  (let [cmd (or java-cmd (java-executable))
        {:keys [classpath-roots argmap]} basis
        cp-entries (concat cp classpath-roots)
        cp-str (deps/join-classpath cp-entries)
        combined-java-opts (concat java-opts (:jvm-opts argmap))]
    {:command-args (make-java-args cmd combined-java-opts cp-str main main-args use-cp-file)}))
