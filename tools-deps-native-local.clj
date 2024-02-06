(require '[babashka.process :as p])

(p/shell {:dir "../tools-deps-native"} "clojure" "-M:run")
