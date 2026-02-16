(defproject domino/core "0.4.0"
            :description "Clojure(script) data flow engine"
            :url "https://github.com/domino-clj/domino"
            :license {:name "Eclipse Public License"
                      :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.12.4" :scope "provided"]
   [org.clojure/clojurescript "1.12.134" :scope "provided"]
   [rhizome "0.2.9"]]

  :plugins
  [[lein-cljsbuild "1.1.8"]
   [lein-figwheel "0.5.20"]
   [cider/cider-nrepl "0.58.0"]
   [lein-doo "0.1.11"]
   [com.jakemccrary/lein-test-refresh "0.26.0"]
   [test2junit "1.4.4"]]

  :test2junit-output-dir "target/test2junit"

  :clojurescript? true
  :jar-exclusions [#"\.swp|\.swo|\.DS_Store"]
  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]]

  :test-selectors {:default   (complement :benchmark)
                   :benchmark :benchmark
                   :all       (constantly true)}

  :profiles
  {:dev
   {:dependencies
    [[reagent "2.0.1"]
     [ring-server "0.5.0"]
     [ring-webjars "0.3.1"]
     [ring "1.15.3"]
     [ring/ring-defaults "0.7.0"]
     [compojure "1.7.2"]
     [hiccup "2.0.0"]
     [nrepl "1.5.1"]
     [binaryage/devtools "1.0.7"]
     [cider/piggieback "0.6.1"]
     [figwheel-sidecar "0.5.20"]
     [cheshire "6.1.0"]
     [pjstadig/humane-test-output "0.11.0"]
     [criterium "0.4.6"]
     [org.clojure/tools.namespace "1.5.1"]]

    :injections [(require 'pjstadig.humane-test-output)
                 (pjstadig.humane-test-output/activate!)]

    :source-paths ["src" "env/dev/clj" "env/dev/cljs"]
    :resource-paths ["resources" "env/dev/resources" "target/cljsbuild"]

    :figwheel
    {:server-port      3450
     :nrepl-port       7000
     :nrepl-middleware [cider.piggieback/wrap-cljs-repl
                        cider.nrepl/cider-middleware]
     :css-dirs         ["resources/public/css" "env/dev/resources/public/css"]
     :ring-handler     domino.server/app}

    :cljsbuild
    {:builds
     {:app
      {:source-paths ["src" "env/dev/cljs"]
       :figwheel     {:on-jsload "domino.test-page/mount-root"}
       :compiler     {:main          domino.dev
                      :asset-path    "/js/out"
                      :output-to     "target/cljsbuild/public/js/app.js"
                      :output-dir    "target/cljsbuild/public/js/out"
                      :source-map-timestamp true
                      :source-map    true
                      :optimizations :none
                      :pretty-print  true}}}}}
   :test
   {:cljsbuild
    {:builds
     {:test
      {:source-paths ["src" "test"]
       :compiler     {:main          domino.runner
                      :output-to     "target/test/core.js"
                      :target        :nodejs
                      :optimizations :none
                      :source-map    true
                      :pretty-print  true}}}}
    :doo {:build "test"}}}
    :aliases
    {#_#_"test"
     ["do"
      ["clean"]
      ["with-profile" "test" "doo" "node" "once"]]
     "test-watch"
     ["do"
      ["clean"]
      ["with-profile" "test" "doo" "node"]]})
