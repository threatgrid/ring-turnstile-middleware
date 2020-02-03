(defproject threatgrid/ring-turnstile-middleware "0.1.1-SNAPSHOT"
  :description "Ring middleware the Turnstile rate limiting service"
  :url "http://github.com/threatgrid/ring-turnstile-middleware"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :pedantic? :abort
  :deploy-repositories [["releases" {:url "https://clojars.org/repo" :creds :gpg}]
                        ["snapshots" {:url "https://clojars.org/repo" :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.logging "0.5.0"]
                 [threatgrid/turnstile "0.104"]
                 [metosin/schema-tools "0.12.2"]
                 [prismatic/schema "1.1.12"]
                 [ring/ring-mock "0.4.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]]
  :global-vars {*warn-on-reflection* true}
  :profile {:dev {:dependencies [[com.taoensso/carmine "2.18.1"]]}})
