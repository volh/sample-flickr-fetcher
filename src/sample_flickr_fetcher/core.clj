(ns sample-flickr-fetcher.core
  (:require [clojure.data.xml :as xml]
            [org.httpkit.client :as http]
            [org.httpkit.server :as server]
            [clojure.java.io :as io]
            [compojure.core :refer :all]
            [ring.middleware.defaults :refer :all]
            [compojure.route :as route])
  (:import [javax.imageio ImageIO]
           [java.awt.image BufferedImage])
  (:gen-class))


(def flickr-feed-url "https://www.flickr.com/services/feeds/photos_public.gne")

(def options { :keepalive 3000 })

(defn parse-resp [{:keys [body]}]
  (xml/parse-str body))

(defn download-feed [callback-fn]
  (http/get flickr-feed-url options
            #(-> (parse-resp %) callback-fn)))

(defn is-entry-tag? [x] (= :entry (:tag x)))

(def find-first (comp first filter))

(defn enclosure-href [{:keys [attrs]}]
  (when (= "enclosure" (:rel attrs)) (:href attrs)))

(defn find-image-link [entry]
  (some enclosure-href (:content entry)))

(defn image-entries [feed] (->> feed :content (filterv is-entry-tag?)))

(defn read-image [url]
  (ImageIO/read (io/as-url url)))

(defn uuid []
  (java.util.UUID/randomUUID))

(defn image-file-name [href]
  (->>
   (clojure.string/split href #"/")
   last
   (str "processed-")))

(defn resize-image
  [url width height]
  (println (str "resizing " url ))
  (try
    (let [img     (read-image url)
          new-img (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
          g       (.createGraphics new-img)]
      (.drawImage g img 0 0 width height nil)
      (.dispose g)
      (javax.imageio.ImageIO/write new-img "png" (io/file (image-file-name url))))
    (catch Exception ex
      (println (str "resizing image failed: " (.getMessage ex))))))

(defn resize-images [width height urls]
  (mapv #(resize-image % width height) urls))

(defn process-images [width height feed]
  (println (str "processing images" width height feed) )
  (->> feed
       image-entries
       (map find-image-link)
       (resize-images width height)))

(defn default-route [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "Hi! Maybe you're interested in /save-images"})

(defn flickr-feed-save-images-handler [req]
  (let [params (:params req)
        width  (Integer/parseInt (:width params))
        height (Integer/parseInt (:height params))]
    (if (and width height)
      (do
        (download-feed (partial process-images width height))
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (str "Downloading flickr images using " width " and " height " dimensions")})

      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    "Please specify width and height arguments"})))

(defroutes app-routes
  (GET "/" [] default-route)
  (GET "/save-images" [] flickr-feed-save-images-handler)
  (route/not-found "Route not found"))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server [port]
  (reset! server (server/run-server
                  (wrap-defaults #'app-routes site-defaults)
                  {:port port})))

(defn -main
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (start-server port)
    (println (str "Server started at http:/127.0.0.1:" port "/"))))
