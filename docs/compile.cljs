#!/usr/bin/env lumo
(ns compile.core
  (:require
   [cljs.tools.reader :as reader :refer [read]]
   [cljs.tools.reader.reader-types :refer [string-push-back-reader read-char]]
   [clojure.string :as string]
   [markdown.core :as markdown]
   ["fs-extra" :as fs]
   ["nunjucks" :as nj])
  (:import goog.string.StringBuffer))

(nj/configure "templates")

(defn slurp [path]
  (fs/readFileSync path #js{:encoding "UTF-8"}))

(defn spit [f content]
  (fs/writeFile f
                content
                (fn [err]
                  (if err (println err) (println "wrote file " f)))))

(defn read-to-eof [rdr]
  (loop [c (read-char rdr)
         s (StringBuffer.)]
    (if c
      (recur (read-char rdr) (.append s c))
      (str s))))

(defn md->html [s]
  (markdown/md->html s))

(defn path [& args]
  (clojure.string/join "/" (remove nil? args)))

;;================= MAIN ============================
(let [readme (slurp (path ".." "README.md"))
      docs (try (fs/readdirSync "md" #js{:encoding "UTF-8"}) (catch js/Error _))
      resources (try (fs/readdirSync "resources" #js{:encoding "UTF-8"}) (catch js/Error _))]
  
  (fs/copySync (path ".." "logo") (path "out" "logo"))
  (doseq [resource resources]
    (fs/copySync (path "resources" resource) (path "out" resource)))

  (spit
   (path "out" "index.html")
   (nj/render
    "page.html"
    (clj->js
     {:content (md->html readme)})))
  
  (doseq [doc docs]
    (spit
     (path "out" (str (first (string/split doc #"\.")) ".html"))
     (nj/render
      "page.html"
      (clj->js
       {:content (->> (path "md" doc)
                      (slurp)
                      (md->html))}))))
 )

