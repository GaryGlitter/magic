(ns magic.analyzer.types
  (:refer-clojure :exclude [resolve])
  (:require [magic.analyzer
             [binder :refer [select-method]]
             [util :refer [throw! var-interfaces var-type] :as util]
             [reflection :refer [find-method]]]
            [magic.emission :refer [*module*]])
  (:import Magic.Runtime
           [System Double Single 
            Int16 Int32 Int64
            UInt16 UInt32 UInt64
            IntPtr UIntPtr Char
            SByte Decimal]))

(defn read-generic-name [name]
  (let [reader (-> name str
                 System.IO.StringReader.
                 clojure.lang.PushbackTextReader.)]
    [(read reader) (read reader)]))

#_
(defn class-for-name [s]
  (if s
    (or (.GetMapping *ns* (symbol (str s)))
        (Runtime/FindType (str s)))))

(defn superchain [t]
  (if-let [b (.BaseType t)]
    (cons b (superchain b))))

(defn zero-arity-type [class name]
  (or (if-let [info (.GetField class name)]
        (.FieldType info))
      (if-let [info (.GetProperty class name)]
        (.PropertyType info))
      (if-let [info (.GetMethod class name Type/EmptyTypes)]
        (.ReturnType info))))

(defn n-arity-type [class method params]
  (if-let [info (.GetMethod class name params)]
    (.ReturnType info)))

(defn zero-arity-info [class name]
  (or (.GetField class name)
      (.GetProperty class name)
      (.GetMethod class name Type/EmptyTypes)))

(def shorthand
  {"float"    Single
   "double"   Double
   "short"    Int16
   "ushort"   UInt16
   "int"      Int32
   "uint"     UInt32
   "long"     Int64
   "ulong"    UInt64
   "bool"     Boolean
   "object"   Object
   "intptr"   IntPtr
   "uintptr"  UIntPtr
   "char"     Char
   "byte"     Byte
   "sbyte"    SByte
   "decimal"  Decimal
   "string"   String
   "floats"   System.Single|[]|
   "doubles"  System.Double|[]|
   "shorts"   System.Int16|[]|
   "ushorts"  System.UInt16|[]|
   "ints"     System.Int32|[]|
   "uints"    System.UInt32|[]|
   "longs"    System.Int64|[]|
   "ulongs"   System.UInt64|[]|
   "bools"    System.Boolean|[]|
   "objects"  System.Object|[]|
   "intptrs"  System.IntPtr|[]|
   "uintptrs" System.UIntPtr|[]|
   "chars"    System.Char|[]|
   "bytes"    System.Byte|[]|
   "sbytes"   System.SByte|[]|
   "decimals" System.Decimal|[]|
   "strings"  System.String|[]|})

(def cached-ns-imports ns-imports)

(defn resolve 
  ([t] (resolve t *ns*))
  ([t ns]
   (cond
     (nil? t)
     nil
     (instance? Type t)
     t
     (symbol? t)
     (recur (str t) ns)
     (string? t)
     (or (shorthand t)
         (and *module*
              (try 
                (.GetType *module* t)
                (catch ArgumentException e
                  nil)))
         (Runtime/FindType t)
         (and ns 
              (get (cached-ns-imports ns) (symbol t))))
     :else
     nil)))
(defn tag [x]
  (if-let [t (-> x meta :tag)]
    (resolve t)))

(defmulti ast-type
  "The CLR type of an AST node"
  :op)

(defn ast-type-or-object [ast]
  (or (ast-type ast)
      Object))

(defn disregard-type? [ast]
  (case (:op ast)
    :if (and (disregard-type? (:then ast))
             (disregard-type? (:else ast)))
    :let (disregard-type? (:body ast))
    :do (disregard-type? (:ret ast))
    (= ::disregard (ast-type ast))))

(def non-void-ast-type
  (memoize
   (fn non-void-ast-type
     ([ast]
      (non-void-ast-type ast Object))
     ([ast non-void-type]
      (if (disregard-type? ast)
        non-void-type
        (let [t (ast-type ast)]
          (if (= t System.Void)
            non-void-type
            t)))))))

;;;;; intrinsics

;; TODO this should be part of a more general conversion system
;; TODO Boolean? Decimal?
(def numeric-promotion-order
  [Byte SByte UInt16 Int16 UInt32 Int32 UInt64 Int64 Single Double])

(def numeric
  (set numeric-promotion-order))

(defn numeric-type? [t]
  (numeric t))

(def integer
  (disj numeric Single Double))

(defn integer-type? [t]
  (integer t))


(defn best-numeric-promotion [types]
  (and (or (every? numeric types) nil)
       (->> types
            (sort-by #(.IndexOf numeric-promotion-order %))
            last)))

;;;;; ast types

(defmethod ast-type :default [ast]
  (throw! "ast-type not implemented for :op " (:op ast) " while analyzing " (-> ast :raw-forms first pr-str)))

(defmethod ast-type :fn
  [{:keys [fn-type]}] clojure.lang.IFn #_fn-type)

(defmethod ast-type :proxy
  [{:keys [proxy-type]}] proxy-type)

(defmethod ast-type :reify
  [{:keys [reify-type]}] reify-type)

(defmethod ast-type :gen-interface [_] System.Type)

(defmethod ast-type :deftype [_] System.Type)

(defmethod ast-type :import [_] System.Type)

(defmethod ast-type :dynamic-constructor [{:keys [type]}] type)

(defmethod ast-type :dynamic-zero-arity [_] Object)

(defmethod ast-type :dynamic-method [_] Object)

(defmethod ast-type :intrinsic
  [{:keys [type]}] type)

(defmethod ast-type :def
  [{:keys [type]}] clojure.lang.Var)

;; TODO remove
(defmethod ast-type nil
  [ast]
  Object
  #_
  (throw! "ast-type not implemented for nil"))

(defmethod ast-type :do
  [{:keys [ret] :as ast}]
  (ast-type ret))

(defmethod ast-type :case
  [{:keys [expressions default] :as ast}]
  (let [result-types (-> #{(ast-type default)}
                         (into (map ast-type expressions))
                         (disj :magic.analyzer.types/disregard))]
    (if (= 1 (count result-types))
      (first result-types)
      Object)))

(defmethod ast-type :set!
  [{:keys [val] {:keys [context]} :env}]
  (if (= context :ctx/statement)
    System.Void
    (ast-type val)))

(def data-structure-types
  {:seq clojure.lang.IPersistentList
   :vector clojure.lang.APersistentVector
   :set clojure.lang.APersistentSet
   :map clojure.lang.APersistentMap})

(defmethod ast-type :quote
  [{:keys [expr] :as ast}]
  (or 
   (data-structure-types (:type expr))
   (ast-type expr)))

(defmethod ast-type :maybe-class
  [{:keys [class] :as ast}]
  (resolve class ast))

(defmethod ast-type :var [ast]
  Object)

(defmethod ast-type :the-var [ast]
  Object)

(defmethod ast-type :const [ast]
  (if (= :class (:type ast))
    System.Type ;; (:val ast) ; oh jesus
    (or (type (:val ast)) Object))) ;; nil -> Object

(defmethod ast-type :vector [ast]
  clojure.lang.APersistentVector)

(defmethod ast-type :set [ast]
  clojure.lang.APersistentSet)

(defmethod ast-type :map [ast]
  clojure.lang.APersistentMap)

(defmethod ast-type :with-meta [{:keys [expr]}]
  clojure.lang.IObj) ;; ??

(defmethod ast-type :static-method [ast]
  (-> ast :method .ReturnType))

(defmethod ast-type :instance-method [ast]
  (-> ast :method .ReturnType))

(defmethod ast-type :static-property [ast]
  (-> ast :property .PropertyType))

(defmethod ast-type :instance-property [ast]
  (-> ast :property .PropertyType))

(defmethod ast-type :static-field [ast]
  (-> ast :field .FieldType))

(defmethod ast-type :instance-field [ast]
  (-> ast :field .FieldType))

;; TODO dynamics always typed as object?
(defmethod ast-type :dynamic-field [ast]
  System.Object)

(defmethod ast-type :invoke
  [{:keys [fn args] :as ast}]
  (resolve
   (or (->> ast
            :meta
            :tag)
       (->> fn
            :meta
            :tag)
       (->> fn
            :meta
            :arglists
            (filter #(= (count %) (count args)))
            first
            meta
            :tag)
       (let [arg-types (map ast-type args)
             target-interfaces (var-interfaces fn)
              ;; TODO this is hacky and gross
             vt (var-type fn)
             invokes (when vt
                       (filter #(= (.Name %) "invokeTyped")
                               (.GetMethods vt)))
             exact-match (when invokes
                           (select-method invokes arg-types))]
         (if exact-match
           (.ReturnType exact-match)
           Object))
       Object)))

(defmethod ast-type :new [ast]
  (:type ast))

(defmethod ast-type :initobj [ast]
  (:type ast))

(defmethod ast-type :maybe-host-form
  [ast]
  (throw! "Trying to find type of :maybe-host-form in " ast))

(defmethod ast-type :host-interop
  [ast]
  (throw! "Trying to find type of :host-interop in " ast))

;; TODO -> form locals :form meta :tag ??
(defmethod ast-type :binding [ast]
  (or
    (if-let [tag (-> ast :form meta :tag)]
      (if (symbol? tag)
        (resolve tag)
        tag))
    (if-let [init (:init ast)]
      (ast-type init))
    Object))

(defmethod ast-type :local
  [{:keys [name form local by-ref?] {:keys [locals]} :env :as ast}]
  (let [tag (or (-> form meta :tag)
                (-> form locals :form meta :tag))
        type (cond tag
                   (if (symbol? tag)
                     (resolve tag)
                     tag)
                   (= local :arg)
                   Object
                   (= local :proxy-this)
                   (or (:proxy-type ast) Object)
                   :else
                   (non-void-ast-type (-> form locals :init)))]
    (if by-ref?
      (.MakeByRefType type)
      type)))

(defmethod ast-type :let [ast]
  (-> ast :body ast-type))

(defmethod ast-type :loop [ast]
  (-> ast :body ast-type))

(defmethod ast-type :letfn [ast]
  (-> ast :body ast-type))

;; ???
(defmethod ast-type :recur [ast]
  ::disregard)

(defmethod ast-type :throw [ast]
  ::disregard)

(defmethod ast-type :try [{:keys [body catches] :as ast}]
  (let [body-type (ast-type body)
        catches-types (->> catches
                           (remove disregard-type?)
                           (map ast-type)
                           (into #{}))]
    (if (or (empty? catches-types)
            (and (= 1 (count catches-types))
                 (= body-type (first catches-types))))
      body-type
      Object)))

(defmethod ast-type :catch [{:keys [body] :as ast}]
  (ast-type body))

(defmethod ast-type :monitor-enter [ast]
  System.Void)

(defmethod ast-type :monitor-exit [ast]
  System.Void)

(defn always-then?
  "Is an if AST node always true?"
  [{:keys [op test then else]}]
  (and (= op :if)
       (or (and (:literal? test)
                (not= (:val test) false)
                (not= (:val test) nil))
           (and (.IsValueType (ast-type test))
                (not= Boolean (ast-type test))))))

(defn always-else?
  "Is an if AST node always false?"
  [{:keys [op test then else]}]
  (and (= op :if)
       (:literal? test)
       (or (= (:val test) false)
           (= (:val test) nil))))

(defmethod ast-type :if
  [{:keys [form test then else] :as ast}]
  (if-let [t (tag form)]
    t
    (let [then-type (ast-type then)
          else-type (ast-type else)]
      (cond
        (= then-type else-type) then-type
        (always-then? ast) then-type
        (always-else? ast) else-type
        (disregard-type? then) else-type
        (disregard-type? else) then-type
        ;; TODO compute common type  
        :else Object))))
