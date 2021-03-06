(ns magic.analyzer.untyped-passes
  (:require 
   [clojure.tools.analyzer.passes :refer [schedule]]
   [clojure.tools.analyzer.ast :refer [nodes update-children]]
   [clojure.tools.analyzer.passes
    [trim :refer [trim]]
    [collect-closed-overs :refer [collect-closed-overs]]
    [uniquify :refer [uniquify-locals]]]
   [magic.analyzer
    [remove-local-children :refer [remove-local-children]]]))

(defn collect-vars
  "Collect all vars within :fn nodes into :vars key"
  [ast]
  (if (= :fn (:op ast))
    (assoc ast :vars (->> ast nodes (filter #(= :var (:op %)))))
    ast))

(def ^:dynamic *stack-empty?* true)

(defn compute-empty-stack-context
  "Stores :empty-stack? into environments reflecting expressions where
   the CLR evaluation stack is expected to be empty"
  {:pass-info {:walk :none}}
  [{:keys [op] {:keys [context]} :env :as ast}]
  (binding [*stack-empty?*
            (or (and *stack-empty?* (not= context :ctx/expr))
                (= op :fn-method))]
    (-> ast
        (assoc-in [:env :empty-stack?] *stack-empty?*)
        (update-children compute-empty-stack-context))))

(defn remove-empty-throw-children
  "Removes :exception from the :children vector of :throw nodes when
   :exception is nil"
  {:pass-info {:walk :any :before #{#'compute-empty-stack-context}}}
  [{:keys [op exception] :as ast}]
  (case op
    :throw
    (if-not exception
      (dissoc ast :children :exception)
      ast)
    ast))

(defn propagate-defn-name
  {:pass-info {:walk :any}}
  [{:keys [op name init] :as ast}]
  (case op
    :def
    (case (:op init)
      :fn
      (assoc-in ast [:init :name] name)
      :with-meta
      (assoc-in ast [:init :expr :name] name)
      ast)
    ast))

(defn extract-form-meta
  {:pass-info {:walk :any}}
  [{:keys [op form] :as ast}]
  (case op
    (:binding) ;; TODO just bindings now to fix issue with parameters
    (assoc ast :meta (meta form))
    ast))

(defn compute-outside-fn
  {:pass-info {:walk :none}}
  [{:keys [op] :as ast}]
  (case op
    :fn
    ast
    #_else
    (-> ast
        (assoc :outside-fn? true)
        (update-children compute-outside-fn))))

(def untyped-pass-set
#{#'collect-vars
  #'propagate-defn-name
  #'compute-outside-fn
  #'extract-form-meta
  #'remove-local-children
  #'collect-closed-overs
  #'trim
  #'uniquify-locals
  #'compute-empty-stack-context
  #'remove-empty-throw-children})

(def scheduled-passes
  (schedule untyped-pass-set))

(defn untyped-passes [ast]
  (scheduled-passes ast))