(ns flow2
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.pprint :as pp]
            [clojure.datafy :as d]))

(set! *warn-on-reflection* true)


(defn monitoring [{:keys [report-chan error-chan]}]
  (prn "========= monitoring start")
  (async/thread
    (loop []
      (let [[val port] (async/alts!! [report-chan error-chan])]
        (if (nil? val)
          (prn "========= monitoring shutdown")
          (do
            (prn (str "======== message from " (if (= port error-chan) :error-chan :report-chan)))
            (pp/pprint val)
            (recur))))))
  nil)


(defn ddupe
  ([] {:ins {:in "stuff"}
       :outs {:out "stuff w/o consecutive dupes"}})
  ([_] {:last nil})
  ([state _] state)
  ([{:keys [last]} _ v]
   [{:last v} (when (not= last v) {:out [v]})]))


(def gdef
  {:procs
   {:craps-finder
    {:proc (-> #(when (#{2 3 12} (apply + %)) %) flow/lift1->step flow/process)}

    :dedupe
    {:proc (flow/process #'ddupe)}

    :prn-sink
    {:proc (flow/process
            (flow/map->step
             {:describe  (fn [] {:ins {:in "gimme stuff to print!"}})
              :transform (fn [_ _ v] (prn v))}))}}
   :conns
   [
    [[:dedupe :out] [:craps-finder :in]]
    [[:craps-finder :out] [:prn-sink :in]]]
   })

(defn inject-dice [g roll]
  (flow/inject g [:dedupe :in] [roll]))

(comment
  (def g (flow/create-flow gdef))
  (pp/pprint (d/datafy g))
  (monitoring (flow/start g))
  ;;wait a bit for craps to print

  (flow/resume g)
  (flow/pause g)

  (flow/pause-proc g :prn-sink)
  (flow/resume-proc g :prn-sink)


  (inject-dice g [(inc (rand-int 6)) (inc (rand-int 6))])
  (inject-dice g [1 2])
  (inject-dice g [6 6])
  (inject-dice g [5 3])

  (pp/pprint (flow/ping g))
  (pp/pprint (flow/ping-proc g :prn-sink))
  (flow/stop g)
  )
