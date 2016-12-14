(ns jepsen.zookeeper
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [avout.core :as avout]
            [knossos.model :as model]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [db :as db]
                    [generator :as gen]
                    [control :as c]
                    [tests :as tests]
                    [nemesis :as nemesis]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]))

(defn zk-node-ids
  "returns a map of node names to node ids"
  [test]
  (->> test
       :nodes
       (map-indexed (fn [i node] [node i]))
       (into {})))

(defn zk-node-id
  "Given a test and a node name from that test, returns the ID for that node."
  [test node]
  ((zk-node-ids test) node))

(defn zoo-cfg-servers
  "augment the default zoo.cfg with the id and hostname"
  [test]
  (->> (zk-node-ids test)
       (map (fn [[node id]]
              (str "server." id "=" (name node) ":2888:3888")))
       (str/join "\n")))

(defn db
  "particular version of ZK db"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (c/su
        (info "Setting up ZK on" node)
        (debian/install {:zookeeper version
                         :zookeeper-bin version
                         :zookeeperd version})

        (c/exec :echo (zk-node-id test node) :> "/etc/zookeeper/conf/myid")

        (c/exec :echo (str (slurp (io/resource "zoo.cfg"))
                           "\n"
                           (zoo-cfg-servers test))
                :> "/etc/zookeeper/conf/zoo.cfg")

        (info node "ZK restarting")
        (c/exec :service :zookeeper :restart)
        (Thread/sleep 5000)
        (info node "ZK ready")))

    (teardown! [_ test node]
      (info "Tearing down ZK on" node)
      (c/su
        (c/exec :service :zookeeper :stop)
        (c/exec :rm :-rf
                (c/lit "/var/lib/zookeeper/version-*")
                (c/lit "/var/log/zookeeper/*"))))

    db/LogFiles
    (log-files [_ test node]
      ["/var/log/zookeeper/zookeeper.log"])
  )
)

(defn r[_ _] {:type :invoke, :f :read, :value nil})
(defn w[_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn client
  "A zookeeper for a single compare and set register"
  [conn a]
  (reify client/Client
    (setup! [_ test node]
      (let [conn (avout/connect (name node))
            a    (avout/zk-atom conn "/jepsen" 0)]
        (client conn a)))

    (invoke! [_ test op]
      (timeout 5000 (assoc op :type :info, :error :timeout)
               (case (:f op)
                :read   (assoc op :type :ok, :value @a)
                :write  (do (avout/reset!! a (:value op))
                            (assoc op :type :ok))
                :cas    (let [[value value'] (:value op)
                              type           (atom :fail)]
                          (avout/swap!! a (fn [current]
                                            (if (= current value)
                                              (do (reset! type :ok)
                                                  value')
                                              (do (reset! type :fail)
                                                  current))))
                          (assoc op :type @type)
                         )
                )
      )
    )

    (teardown! [_ test]
      (.close conn))))


(defn zk-test
  "doc for zk-test"
  [opts]
  (merge
    tests/noop-test
    {:nodes (:nodes opts)
     :ssh   (:ssh opts)
     :name "zookeeper"
     :os debian/os
     :db (db "3.4.5+dfsg-2")
     :client (client nil nil)
     :generator (->> (gen/mix [r w cas])
                     (gen/stagger 1)
                     (gen/nemesis (gen/seq
                                    (cycle [(gen/sleep 5)
                                            {:type :info, :f :start}
                                            (gen/sleep 5)
                                            {:type :info, :f :stop}])))
                     (gen/time-limit 15))
     :nemesis (nemesis/partition-random-halves)
     :model   (model/cas-register 0)
     :checker (checker/compose
                {:perf   (checker/perf)
                 :html   (timeline/html)
                 :linear checker/linearizable}) }))


(defn -main
  "this is the doc for the main func"
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn zk-test})
                   (cli/serve-cmd))
            args))
