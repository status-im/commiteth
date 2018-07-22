(ns commiteth.handler
  (:require [compojure.core :refer [routes wrap-routes]]
            [commiteth.layout :refer [error-page]]
            [commiteth.routes.home :refer [home-routes]]
            [commiteth.routes.redirect :refer [redirect-routes]]
            [commiteth.routes.services :refer [service-routes]]
            [commiteth.routes.webhooks :refer [webhook-routes]]
            [commiteth.routes.qrcodes :refer [qr-routes]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [commiteth.env :refer [defaults]]
            [mount.core :as mount]
            [commiteth.middleware :as middleware]
            [commiteth.config :refer [env]]))

(mount/defstate init-app
  :start ((or (:init defaults) identity))
  :stop ((or (:stop defaults) identity)))

(def app-routes
  (routes
   #'webhook-routes
   (middleware/wrap-base
    (routes
     (-> #'home-routes
         (wrap-routes middleware/wrap-csrf)
         (wrap-routes middleware/wrap-formats))
     #'redirect-routes
     #'service-routes
     #'qr-routes
     (route/files "/qr-image" {:root (:qr-dir env)})
     (route/not-found
      (:body
       (error-page {:status 404
                    :title  "page not found"})))))))


(defn app []
  #'app-routes)
