(ns flow1
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


(defn roller-thread [pace-ms]
  (let [chan (async/chan)]
    (async/thread
      (loop []
        (if (async/>!! chan [(inc (rand-int 6)) (inc (rand-int 6))])
          (do (Thread/sleep (long (rand-int pace-ms)))
              (recur))
          (prn "roller thread stopped"))))
    chan))


(defn roller-proc
  ([] {:params {:pace-ms "range of roll intervals"}
       :outs {:out "roll the dice!"}})
  ([{:keys [pace-ms]}] {::flow/in-ports {:in (roller-thread pace-ms)}})
  ([state status]
   (when (= status ::flow/stop)
     (async/close! (-> state ::flow/in-ports :in)))
   state)
  ([state _ v] [state {:out [v]}]))


(def gdef
  {:procs
   {:dice-source
    {:proc (flow/process #'roller-proc)
     :args {:pace-ms 1000}}

    :craps-finder
    {:proc (-> #(when (#{2 3 12} (apply + %)) %) flow/lift1->step flow/process)}

    :dedupe
    {:proc (flow/process #'ddupe)}

    :prn-sink
    {:proc (flow/process
            (flow/map->step
             {:describe (fn [] {:ins {:in "gimme stuff to print!"}})
              :transform (fn [_ _ v] (prn v))}))}}
   :conns
   [
    [[:dice-source :out] [:dedupe :in]]
    [[:dedupe :out] [:craps-finder :in]]
    [[:craps-finder :out] [:prn-sink :in]]]
   })

(comment
  (def g (flow/create-flow gdef))
  (pp/pprint (d/datafy g))
  (monitoring (flow/start g))
  ;;wait a bit for craps to print

  (flow/resume g)
  (flow/pause g)

  (flow/pause-proc g :prn-sink)
  (flow/resume-proc g :prn-sink)
  (flow/inject g [:craps-finder :in] [[1 2] [2 1] [2 1] [6 6] [6 6] [4 3] [1 1]])
  (pp/pprint (flow/ping g))
  (pp/pprint (flow/ping-proc g :prn-sink))
  (flow/stop g)
  )
