(defproject jvm-on-clj "0.1.0-SNAPSHOT"
  :description "JVM on Clojure"
  :url "https://github.com/rinx/jvm-on-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]]
  :profiles {:uberjar {:aot :all
                       :main jvm-on-clj.core}}
  :repl-options {:init-ns jvm-on-clj.core})
