(ns tatara.runner.burst-test
  "AWAI-3834: the on-demand APK pool is created on demand, deleted when idle,
  and no other pool is ever a candidate (negative tests)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [tatara.runner.burst :as b]))

(def cfg {:label "awai-apk-burst" :plan "vc2-4c-8gb" :idle-ms 900000 :max-pool-ms (* 4 3600000)})
(def t0 1800000000000)
(defn iso [ms] (str (java.time.OffsetDateTime/ofInstant (java.time.Instant/ofEpochMilli ms) java.time.ZoneOffset/UTC)))

;; Pools as the Vultr API returns them (labels parsed with keyword keys).
(defn parse [m] (json/parse-string (json/generate-string m) true))
(def system (parse {:id "sys" :label "tatara-system" :labels {"tatara.network/role" "system"} :date_created (iso 0)}))
(def runners (parse {:id "run" :label "tatara-ci-runners-1c" :labels {"tatara.network/role" "runner"} :date_created (iso 0)}))
(def release (parse {:id "rel" :label "awai-pc-release-90e9" :labels {"awai.network/reapable" "true"} :date_created (iso 0)}))
(def impostor (parse {:id "imp" :label "awai-apk-burst" :labels {"tatara.network/role" "runner"} :date_created (iso t0)}))
(def ours (parse {:id "apk" :label "awai-apk-burst"
                  :labels {"awai.network/burst" "awai-apk-burst"} :date_created (iso t0)}))
(def others [system runners release impostor])

(deftest creates-on-demand
  (is (= :create (:action (b/decide cfg {} {:demand 1 :pools others :now t0}))))
  (is (= :none (:action (b/decide cfg {} {:demand 1 :pools (conj others ours) :now t0}))) "one pool at most"))

(deftest deletes-after-idle-only
  (let [d1 (b/decide cfg {} {:demand 0 :pools (conj others ours) :now (+ t0 1000)})]
    (is (= :none (:action d1)))
    (is (= :none (:action (b/decide cfg d1 {:demand 0 :pools (conj others ours) :now (+ t0 1000 899000)}))))
    (let [d (b/decide cfg d1 {:demand 0 :pools (conj others ours) :now (+ t0 1000 900000)})]
      (is (= :delete (:action d)))
      (is (= "apk" (get-in d [:pool :id]))))
    (testing "new demand resets the idle clock"
      (let [d2 (b/decide cfg d1 {:demand 2 :pools (conj others ours) :now (+ t0 500000)})]
        (is (nil? (:idle-since d2)))
        (is (= :none (:action (b/decide cfg d2 {:demand 0 :pools (conj others ours) :now (+ t0 1000000)}))))))))

(deftest max-pool-age-bounds-a-stuck-run
  (is (= :delete (:action (b/decide cfg {} {:demand 1 :pools [ours] :now (+ t0 (* 4 3600000) 1)})))))

(deftest never-touches-another-pool
  (testing "no demand and long idle: nothing to delete when only other pools exist"
    (doseq [now [t0 (+ t0 (* 10 3600000))]]
      (is (= :none (:action (b/decide cfg {:idle-since 0} {:demand 0 :pools others :now now}))))))
  (testing "the same label without our node label is not ours"
    (is (not (b/ours? cfg impostor)))
    (is (not-any? #(b/ours? cfg %) others))
    (is (b/ours? cfg ours)))
  (testing "act! re-reads the pool and refuses a delete of a pool that is not ours"
    (let [calls (atom [])]
      (with-redefs-fn {#'b/pools (fn [_] others)
                       #'b/api (fn [& args] (swap! calls conj (vec (rest args))) nil)}
        (fn []
          (b/act! (assoc cfg :dry-run? false :cluster "c") {:action :delete :pool release :reason "test"})
          (b/act! (assoc cfg :dry-run? false :cluster "c") {:action :delete :pool impostor :reason "test"})
          (b/act! (assoc cfg :dry-run? false :cluster "c") {:action :delete :pool runners :reason "test"})))
      (is (empty? @calls) "no Vultr call for any pool that is not the lane's")
      (testing "control: the lane's own pool is deleted through the API"
        (with-redefs-fn {#'b/pools (fn [_] (conj others ours))
                         #'b/api (fn [& args] (swap! calls conj (vec (rest args))) nil)}
          #(b/act! (assoc cfg :dry-run? false :cluster "c") {:action :delete :pool ours :reason "idle"}))
        (is (= [[:delete "/kubernetes/clusters/c/node-pools/apk"]] @calls))))))

(deftest dry-run-calls-no-api
  (with-redefs [b/pools (fn [_] [ours])]
    (let [out (with-out-str
                (b/act! (assoc cfg :dry-run? true :cluster "c") {:action :create :reason "demand 1"})
                (b/act! (assoc cfg :dry-run? true :cluster "c") {:action :delete :pool ours :reason "idle"}))]
      (is (re-find #"create pool awai-apk-burst vc2-4c-8gb - demand 1 \(dry run\)" out))
      (is (re-find #"delete pool apk awai-apk-burst - idle \(dry run\)" out)))))

(deftest the-pool-is-tainted-and-reapable
  (let [s (b/pool-spec cfg)]
    (is (= "true" (get-in s [:labels "awai.network/reapable"])))
    (is (= "ci-burst" (get-in s [:labels "awai.network/purpose"])))
    (is (= [{:key "awai.network/apk" :value "build" :effect "NoSchedule"}] (:taints s)))
    (is (= [1 1 1 false] ((juxt :node_quantity :min_nodes :max_nodes :auto_scaler) s)))))
