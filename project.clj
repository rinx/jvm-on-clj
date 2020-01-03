(defproject jvm-on-clj "0.1.0-SNAPSHOT"
  :description "JVM on Clojure"
  :url "https://github.com/rinx/jvm-on-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.9.0"]]
  :plugins [[io.taylorwood/lein-native-image "0.3.1"]]
  :native-image {:name "jvm-on-clj"
                 :opts ["--report-unsupported-elements-at-runtime"
                        "--initialize-at-build-time"
                        "--no-server"
                        "--no-fallback"
                        "--verbose"
                        "-H:+ReportExceptionStackTraces"
                        "-H:ReflectionConfigurationFiles=reflection.json"]
                 :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
  :profiles {:uberjar {:aot :all}}
  :main jvm-on-clj.core
  :repl-options {:init-ns jvm-on-clj.core})
