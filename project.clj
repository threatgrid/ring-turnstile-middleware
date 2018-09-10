(defproject threatgrid/ring-turnstile-middleware "0.0.1-SNAPSHOT"
  :description "Ring middleware the Turnstile rate limiting service"
  :url "http://github.com/threatgrid/ring-turnstile-middleware"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :deploy-repositories [["releases" {:url "https://clojars.org/repo" :creds :gpg}]
                        ["snapshots" {:url "https://clojars.org/repo" :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [msprunck/turnstile "0.101-SNAPSHOT"]
                 [metosin/schema-tools "0.10.4"]
                 [prismatic/schema "1.1.9"]
                 [ring/ring-mock "0.3.2"]]
  :profile {:dev {:dependencies [[com.taoensso/carmine "2.18.1"]]}})

