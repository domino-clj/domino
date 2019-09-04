(ns domino.server
  (:require [compojure.core :refer [GET defroutes]]
            [compojure.route :refer [not-found resources]]
            [hiccup.page :refer [include-js include-css html5]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.util.response :as response]))

(def mount-target
  [:div#app
   [:h3 "ClojureScript has not been compiled!"]
   [:p "please run "
    [:b "lein figwheel"]
    " in order to start the compiler"]])

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name    "viewport"
           :content "width=device-width, initial-scale=1"}]
   (include-css "/css/base.css"
                "/css/site.css")])

(defn loading-page []
  (html5
    (head)
    [:body {:class "body-container"}
     mount-target
     (include-js "/js/app.js")]))

(defroutes routes
  (GET "/" []
    (-> (response/response (loading-page))
        (response/content-type "text/html")))
  (resources "/" {:root "public"})
  (not-found "Not Found"))

(def app (wrap-reload #'routes {:dir ["env/dev/clj"]}))
