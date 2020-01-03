(ns jvm-on-clj.core
  (:require
   [clojure.string :as string])
  (:gen-class))

(def java-classes
  {"java/lang/System"
   {:static-fields
    {"out" {:fp println}}}
   "java/io/PrintStream"
   {:methods
    {"println" (fn [& args]
                 (let [ps (first args)
                       f (:fp ps)
                       snd (second args)]
                   (f snd)))}}})

(def op-stack (atom []))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

(defn bytes->hex [bytes]
  "Converts a byte array into a hex."
  (->> bytes
       (map (partial format "%02x"))
       (apply (partial str "0x"))))

(defn bytes->int [bytes]
  "Converts a byte array into an integer."
  (->> bytes
       (map (partial format "%02x"))
       (apply (partial str "0x"))
       read-string))

(defn take-hex [bytes begin len]
  (bytes->hex (byte-array (take len (drop begin bytes)))))

(defn take-int [bytes begin len]
  (bytes->int (byte-array (take len (drop begin bytes)))))

(defn idx-> [constant-pool idx]
  (-> constant-pool
      (get (dec idx))))

(defn idx->string [constant-pool idx]
  (-> constant-pool
      (idx-> idx)
      :bytes
      (String.)))

(defn resolve-idx [constant-pool idx]
  (let [fetched (idx-> constant-pool idx)]
    (case (:type fetched)
      :method-ref
      (let [class-idx (:class-index fetched)
            name-and-type-idx (:name-and-type-index fetched)]
        {:class (resolve-idx constant-pool class-idx)
         :name-and-type (resolve-idx constant-pool name-and-type-idx)})
      :class
      (let [name-idx (:name-index fetched)]
        {:name (resolve-idx constant-pool name-idx)})
      :string
      (let [string-idx (:string-index fetched)]
        {:string (resolve-idx constant-pool string-idx)})
      :field-ref
      (let [class-idx (:class-index fetched)
            name-and-type-idx (:name-and-type-index fetched)]
        {:class (resolve-idx constant-pool class-idx)
         :name-and-type (resolve-idx constant-pool name-and-type-idx)})
      :utf8
      {:length (:length fetched)
       :string (String. (:bytes fetched))}
      :name-and-type
      (let [name-idx (:name-index fetched)
            descriptor-idx (:descriptor-index fetched)]
        {:name (resolve-idx constant-pool name-idx)
         :descriptor (resolve-idx constant-pool descriptor-idx)}))))

(defn take-constant-pool [bytes constant-pool-count]
  (loop [bytes bytes
         i 0
         result []]
    (if (< i (- constant-pool-count 1))
      (let [tag-hex (take-hex bytes 0 1)
            i' (inc i)]
        (case tag-hex
          "0x0a" (recur (drop 5 bytes) i' (conj result {:type :method-ref
                                                        :class-index (take-int bytes 1 2)
                                                        :name-and-type-index (take-int bytes 3 2)}))
          "0x07" (recur (drop 3 bytes) i' (conj result {:type :class
                                                        :name-index (take-int bytes 1 2)}))
          "0x08" (recur (drop 3 bytes) i' (conj result {:type :string
                                                        :string-index (take-int bytes 1 2)}))
          "0x09" (recur (drop 5 bytes) i' (conj result {:type :field-ref
                                                        :class-index (take-int bytes 1 2)
                                                        :name-and-type-index (take-int bytes 3 2)}))
          "0x01" (let [ln (take-int bytes 1 2)
                       drops (+ 1 2 ln)
                       read-bytes (byte-array (take ln (drop 3 bytes)))]
                   (recur (drop drops bytes) i' (conj result {:type :utf8
                                                              :length ln
                                                              :bytes read-bytes})))
          "0x0c" (recur (drop 5 bytes) i' (conj result {:type :name-and-type
                                                        :name-index (take-int bytes 1 2)
                                                        :descriptor-index (take-int bytes 3 2)}))
          (throw (Exception. (str "cannot parse constant-pool.: " tag-hex)))))
      {:result result
       :bytes bytes})))

(defn take-exception-table [bytes exception-table-count]
  (loop [bytes bytes
         i 0
         result []]
    (if (< i exception-table-count)
      (let [i' (inc i)]
        (recur (drop 8 bytes) i' (conj result {:start-pc (take-int bytes 0 2)
                                               :end-pc (take-int bytes 2 2)
                                               :handler-pc (take-int bytes 4 2)
                                               :catch-type (take-int bytes 6 2)})))
      {:result result
       :bytes bytes})))

(defn take-attribute-info [constant-pool bytes attributes-count]
  (loop [bytes bytes
         i 0
         result []]
    (if (< i attributes-count)
      (let [i' (inc i)
            attribute-name (idx->string constant-pool (take-int bytes 0 2))
            attribute-length (take-int bytes 2 4)
            attribute-body (take attribute-length (drop 6 bytes))
            bytes' (drop (+ 6 attribute-length) bytes)]
        (recur bytes' i' (conj result {:attribute-name attribute-name
                                               :attribute-length attribute-length})))
      {:result result
       :bytes bytes})))

(defn take-code-attributes [constant-pool bytes code-attributes-count]
  (loop [bytes bytes
         i 0
         result []]
    (if (< i code-attributes-count)
      (let [attribute-name (idx->string constant-pool (take-int bytes 0 2))
            attribute-length (take-int bytes 2 4)
            max-stack (take-int bytes 6 2)
            max-locals (take-int bytes 8 2)
            code-length (take-int bytes 10 4)
            code (byte-array (take code-length (drop 14 bytes)))
            bytes' (drop (+ code-length 14) bytes)
            exception-table-length (take-int bytes' 0 2)
            exception-table-res (take-exception-table (drop 2 bytes') exception-table-length)
            exception-table (:result exception-table-res)
            bytes'' (:bytes exception-table-res)
            attributes-count (take-int bytes'' 0 2)
            attributes-res (take-attribute-info constant-pool (drop 2 bytes'') attributes-count)
            attributes (:result attributes-res)
            bytes''' (:bytes attributes-res)
            i' (inc i)]
        (recur bytes''' i' (conj result {:attribute-name attribute-name
                                         :attribute-length attribute-length
                                         :max-stack max-stack
                                         :max-locals max-locals
                                         :code-length code-length
                                         :code code
                                         :exception-table-length exception-table-length
                                         :exception-table exception-table
                                         :attributes-count attributes-count
                                         :attributes attributes})))
      {:result result
       :bytes bytes})))

(defn take-methods-info [constant-pool bytes methods-count]
  (loop [bytes bytes
         i 0
         result []]
    (if (< i methods-count)
      (let [access-flags (take-int bytes 0 2)
            name (idx->string constant-pool (take-int bytes 2 2))
            descriptor (idx->string constant-pool (take-int bytes 4 2))
            attributes-count (take-int bytes 6 2)
            attributes-res (take-code-attributes constant-pool (drop 8 bytes) attributes-count)
            attributes (:result attributes-res)
            i' (inc i)
            bytes' (:bytes attributes-res)]
        (recur bytes' i' (conj result {:access-flags access-flags
                                       :name name
                                       :descriptor descriptor
                                       :attributes-count attributes-count
                                       :attributes attributes})))
      {:result result
       :bytes bytes})))

(defn read-class [whole-bytes]
  (let [magic (take-hex whole-bytes 0 4)
        minor-version (take-int whole-bytes 4 2)
        major-version (take-int whole-bytes 6 2)
        constant-pool-count (take-int whole-bytes 8 2)
        constant-pool-res (take-constant-pool (drop 10 whole-bytes) constant-pool-count)
        constant-pool (:result constant-pool-res)
        whole-bytes' (:bytes constant-pool-res)
        access-flags (take-int whole-bytes' 0 2)
        this-class (take-int whole-bytes' 2 2)
        super-class (take-int whole-bytes' 4 2)
        interface-count (take-int whole-bytes' 6 2)
        fields-count (take-int whole-bytes' 8 2)
        ;; fields-(take-fields )
        methods-count (take-int whole-bytes' 10 2)
        methods-info-res (take-methods-info constant-pool (drop 12 whole-bytes') methods-count)
        methods-info (:result methods-info-res)]
    {:magic magic
     :minor-version minor-version
     :major-version major-version
     :constant-pool-count constant-pool-count
     :constant-pool constant-pool
     :access-flags access-flags
     :this-class this-class
     :super-class super-class
     :interface-count interface-count
     :fields-count fields-count
     :methods-count methods-count
     :methods-info methods-info}))

(defn execute-opcodes [constant-pool opcodes]
  (let [opcode (take-hex opcodes 0 1)
        rest (drop 1 opcodes)]
    (case opcode
      "0xb2" ;; getstatic
      (let [idx (take-int rest 0 2)
            obj (resolve-idx constant-pool idx)]
        #_(clojure.pprint/pprint obj)
        (swap! op-stack #(conj % obj)))
      "0x12" ;; ldc
      (let [idx (take-int rest 0 1)
            obj (resolve-idx constant-pool idx)]
        #_(clojure.pprint/pprint obj)
        (swap! op-stack #(conj % obj)))
      "0xb6" ;; invokevirtual
      (let [idx (take-int rest 0 2)
            obj (resolve-idx constant-pool idx)
            arg-count (count (re-seq #";" (get-in obj [:name-and-type :descriptor :string])))
            op-stack' (deref op-stack)
            args (reverse (take arg-count (reverse op-stack')))
            args-entity (map #(get-in % [:string :string]) args)
            callee-class (first (drop arg-count (reverse op-stack')))
            callee-class-entity (get java-classes (get-in callee-class [:class :name :string]))
            callee-method-entity (:fp (get-in callee-class-entity
                                              [:static-fields
                                               (get-in callee-class [:name-and-type :name :string])]))
            ]
        (swap! op-stack #(reverse (drop arg-count (reverse %))))
        #_(clojure.pprint/pprint obj)
        (apply callee-method-entity args-entity))
      "0xb1" ;; return
      ;; return void
      nil
      nil)
    (if-not (empty? rest)
      (execute-opcodes constant-pool rest)
      nil)))

(defn execute [constant-pool code-attributes]
  (let [attr (first code-attributes)
        rest (drop 1 code-attributes)
        code (:code attr)]
    (execute-opcodes constant-pool code)
    (if-not (empty? rest)
      (execute constant-pool rest)
      nil)))

(defn invoke [constant-pool method-info]
  (let [code-attributes (:attributes method-info)]
    (execute constant-pool code-attributes)))

(defn -main [& args]
  (let [filename (or (first args) "examples/Hello.class")
        whole-bytes (slurp-bytes filename)
        read-class (read-class whole-bytes)
        constant-pool (:constant-pool read-class)
        methods (:methods-info read-class)]
    (->> methods
         (map (fn [m]
                (when (= (:name m) "main")
                  (invoke constant-pool m))))
         (doall))
    read-class))

(comment
  (bytes->int (byte-array (take 2 (drop 8 (slurp-bytes "examples/Hello.class")))))

  (println (bytes->hex (slurp-bytes "examples/Hello.class")))
  )
