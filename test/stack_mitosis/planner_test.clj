(ns stack-mitosis.planner-test
  (:require [clojure.test :refer :all]
            [stack-mitosis.planner :as plan]
            [stack-mitosis.operations :as op]))

(deftest list-tree
  (let [a {:DBInstanceIdentifier :a :ReadReplicaDBInstanceIdentifiers [:b]}
        b {:DBInstanceIdentifier :b :ReadReplicaDBInstanceIdentifiers [:c]}
        c {:DBInstanceIdentifier :c :ReadReplicaDBInstanceIdentifiers []}]
    (is (= [:a :b :c] (plan/list-tree [a b c] :a))))
  (let [a {:DBInstanceIdentifier :a :ReadReplicaDBInstanceIdentifiers [:b :c]}
        b {:DBInstanceIdentifier :b :ReadReplicaDBInstanceIdentifiers [:d]}
        c {:DBInstanceIdentifier :c :ReadReplicaDBInstanceIdentifiers []}
        d {:DBInstanceIdentifier :c :ReadReplicaDBInstanceIdentifiers []}]
    (is (= [:a :b :c :d] (plan/list-tree [a b c d] :a)))))

(deftest copy-tree
  (let [instances [{:DBInstanceIdentifier "source"}
                   {:DBInstanceIdentifier "target" :ReadReplicaDBInstanceIdentifiers ["a" "b"]}
                   {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]
                    :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "b" :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "c" :ReadReplicaSourceDBInstanceIdentifier "b"}]]
    (is (= [(op/create-replica "source" "temp-target")
            (op/create-replica "temp-target" "temp-a")
            (op/create-replica "temp-target" "temp-b")
            (op/create-replica "temp-b" "temp-c")
            (op/promote "temp-target")]
           (plan/copy-tree instances "source" "target" (partial plan/transform "temp"))))))

(deftest rename-tree
  (let [instances [{:DBInstanceIdentifier "root" :ReadReplicaDBInstanceIdentifiers ["a" "b"]}
                   {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]}
                   {:DBInstanceIdentifier "b"}
                   {:DBInstanceIdentifier "c"}]]
    (is (= [(op/rename "b" "temp-b")
            (op/rename "c" "temp-c")
            (op/rename "a" "temp-a")
            (op/rename "root" "temp-root")]
           (plan/rename-tree instances "root" (partial plan/aliased "temp"))))))

(deftest delete-tree
  (let [instances [{:DBInstanceIdentifier "source"}
                   {:DBInstanceIdentifier "target" :ReadReplicaDBInstanceIdentifiers ["a" "b"]}
                   {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]
                    :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "b" :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "c" :ReadReplicaSourceDBInstanceIdentifier "b"}]]
    (is (= [(op/delete "b")
            (op/delete "c")
            (op/delete "a")
            (op/delete "target")]
           (plan/delete-tree instances "target")))))

(deftest replace-tree
  (let [instances [{:DBInstanceIdentifier "production"}
                   {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]}
                   {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"}]]
    (is (= [(op/create-replica "production" "temp-staging")
            (op/create-replica "temp-staging" "temp-staging-replica")
            (op/promote "temp-staging")
            (op/rename "staging-replica" "old-staging-replica")
            (op/rename "staging" "old-staging")
            (op/rename "temp-staging-replica" "staging-replica")
            (op/rename "temp-staging" "staging")
            (op/delete "old-staging-replica")
            (op/delete "old-staging")]
           (plan/replace-tree instances "production" "staging")))))

(deftest skippable
  (let [instances [{:DBInstanceIdentifier "a"}
                   {:DBInstanceIdentifier "b"}]]
    (is (= {:op :CreateDBInstance
            :request {:DBInstanceIdentifier "a"}
            :skip (plan/duplicate-instance "a")}
           (plan/skippable instances (op/create {:DBInstanceIdentifier "a"}))))
    (is (= {:op :CreateDBInstanceReadReplica
            :request {:DBInstanceIdentifier "b" :SourceDBInstanceIdentifier "a"}
            :skip (plan/duplicate-instance "b")}
           (plan/skippable instances (op/create-replica "a" "b"))))))
