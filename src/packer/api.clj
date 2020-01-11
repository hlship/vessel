(ns packer.api
  (:require [clojure.data.json :as json]
            [packer.builder :as builder]
            [packer.image :as image]
            [packer.jib :as jib]
            [packer.misc :as misc])
  (:import java.io.Writer))

(defmacro ^:private with-elapsed-time
  "Evaluates body and shows a log message by displaying the elapsed time in the process.

  Returns whatever body yelds."
  [& body]
  `(let [start#  (misc/now)
         result# (do ~@body)]
     (misc/log :info "packer" "Successfully containerized in %s"
               (misc/duration->string (misc/duration-between start# (misc/now))))
     result#))

(defn containerize
  "Containerize a Clojure application."
  [options]
  (with-elapsed-time
    (let [opts (assoc options :target-dir (misc/make-empty-dir ".packer"))]
      (-> (builder/build-app options)
          (image/render-image-spec options)
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
  (let [merge-all      (partial apply merge)
        image-manifest (-> {:image (into {:repository repository :registry registry} attributes)}
                           (misc/assoc-some :base-image base-image)
                           (merge-all manifests))
        image-tag      (or tag (misc/sha-256 image-manifest))]
    (write-manifest output (assoc-in image-manifest [:image :tag] image-tag))))
