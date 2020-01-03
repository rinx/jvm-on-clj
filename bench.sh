#/bin/bash

echo "### JVM"
echo "(cd examples && time java Hello)"
(cd examples && time java Hello)

echo "### JVM on JVM"
echo "time java -jar target/jvm-on-clj-0.1.0-SNAPSHOT-standalone.jar"
time java -jar target/jvm-on-clj-0.1.0-SNAPSHOT-standalone.jar

echo "### JVM on GraalVM native image"
echo "time ./target/jvm-on-clj"
time ./target/jvm-on-clj
