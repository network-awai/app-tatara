(ns tatara.runner.scaler-test
  (:require [clojure.test :refer [deftest is testing]]
            [tatara.runner.scaler :as s]))

(def opts {:min-replicas 0 :max-replicas 3 :idle-ms 600000})

(deftest demand-counts-open-jobs-of-pending-runs
  (is (= 3 (s/demand [{:status :pending :replicas [{:status :queued} {:status :leased} {:status :done}]}
                      {:status :pending :replicas [{:status :queued}]}
                      {:status :reached :replicas [{:status :queued} {:status :done}]}
                      {:status :hard-error :replicas [{:status :leased}]}])))
  (is (= 0 (s/demand []))))

(deftest decide
  (testing "scales up at once, capped at max"
    (is (= {:replicas 2 :idle-since nil} (s/decide opts {:current 0 :demand 2 :idle-since nil :now 0})))
    (is (= {:replicas 3 :idle-since nil} (s/decide opts {:current 1 :demand 9 :idle-since 5 :now 10}))))
  (testing "never scales down while there is demand"
    (is (= {:replicas 3 :idle-since nil} (s/decide opts {:current 3 :demand 1 :idle-since nil :now 0}))))
  (testing "starts the idle clock, then scales to min only after idle-ms"
    (is (= {:replicas 2 :idle-since 100} (s/decide opts {:current 2 :demand 0 :idle-since nil :now 100})))
    (is (= {:replicas 2 :idle-since 100} (s/decide opts {:current 2 :demand 0 :idle-since 100 :now 600099})))
    (is (= {:replicas 0 :idle-since 100} (s/decide opts {:current 2 :demand 0 :idle-since 100 :now 600100}))))
  (testing "respects a non-zero minimum"
    (is (= {:replicas 1 :idle-since 0} (s/decide (assoc opts :min-replicas 1) {:current 0 :demand 0 :idle-since nil :now 0})))
    (is (= {:replicas 1 :idle-since 0} (s/decide (assoc opts :min-replicas 1) {:current 3 :demand 0 :idle-since 0 :now 600000})))))

(deftest an-unreachable-coordinator-counts-as-no-demand
  (is (= {:demand 4} (s/observed-demand (constantly 4))))
  (let [r (s/observed-demand #(throw (ex-info "coordinator unreachable" {:err "timeout"})))]
    (is (= 0 (:demand r)))
    (is (= "coordinator unreachable" (:error r))))
  (testing "so an outage longer than idle-ms scales to the minimum"
    (is (= {:replicas 0 :idle-since 0}
           (s/decide opts {:current 3 :demand (:demand (s/observed-demand #(throw (Exception. "x"))))
                           :idle-since 0 :now 600000})))))
