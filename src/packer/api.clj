(ns packer.api
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [packer.image :as image]
            [packer.jib :as jib]
            [packer.misc :as misc :refer [with-clean-dir]])
  (:import [java.io File Writer]
           [java.util.jar JarEntry JarFile]))

(defmacro ^:private with-elapsed-time
  "Evaluates body and shows a log message by displaying the elapsed time in the process.

  Returns whatever body yelds."
  [& body]
  `(let [start#  (misc/now)
         result# (do ~@body)]
     (misc/log :info "packer" "Successfully containerized in %s"
               (misc/duration->string (misc/duration-between start# (misc/now))))
     result#))

(defn- get-project-insights
  "List project sources and resources in order to obtain insights about
  how image layers must be organized."
  [^File project-root]
  {:known-sources   (misc/find-files-at (io/file project-root "src"))
   :known-resources (misc/find-files-at (io/file project-root "resources"))})

(defn- unpack-jar
  "Extracts all files from the jar in question into the destination
  directory.

  Returns a shallow list of the extracted files."
  [^File jar ^File dest-dir]
  (with-open [jar-file (JarFile. jar)]
    (misc/log :info "packer" "extracting %s into %s directory..." (.getName jar) (.getPath dest-dir))
    (->> jar-file
         .entries
         enumeration-seq
         (run! (fn [^JarEntry jar-entry]
                 (let [^File file (io/file dest-dir (.getName jar-entry))]
                   (if (.isDirectory jar-entry)
                     (.mkdir file)
                     (do (io/make-parents file)
                         (io/copy (.getInputStream jar-file jar-entry) file)))))))
    (misc/find-files-at dest-dir)))

(defn containerize
  "Turns an input jar into a lightweight container according to the
  provided options."
  [{:keys [^File jar-file ^File project-root] :as context}]
  {:pre [jar-file project-root]}
  (with-elapsed-time
    (let [files (with-clean-dir [dest-dir (io/file ".packer")]
                  (unpack-jar jar-file dest-dir))]
      (-> (into context (get-project-insights project-root))
          (assoc :files files)
          image/render-image-spec
          jib/containerize))))

(defn- write-manifest
  "Writes the manifest to the output as a JSON object."
  [^Writer output manifest]
  (binding [*out* output]
    (println (json/write-str manifest))))

(defn manifest
  [{:keys [attributes  object output]}]
  {:pre [attributes  object output]}
  (->> (into {}  attributes)
       (assoc {} object)
       (write-manifest output)))

(defn image
  [{:keys [attributes base-image manifests output registry repository tag]}]
  {:pre [output registry repository]}
  (let [merge-all (partial apply merge)
        image-manifest (-> {:image (into {:repository repository :registry registry} attributes)}
                           (misc/assoc-some :base-image base-image)
                           (merge-all manifests))
        image-tag (or tag (misc/sha-256 image-manifest))]
    (write-manifest output (assoc-in image-manifest [:image :tag] image-tag))))
