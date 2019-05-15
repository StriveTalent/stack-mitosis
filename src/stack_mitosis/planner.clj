(ns stack-mitosis.planner
  (:require [clojure.string :as str]
            [stack-mitosis.helpers :as helpers
             :refer [bfs-tree-seq topological-sort update-if]]
            [stack-mitosis.lookup :as lookup]
            [stack-mitosis.operations :as op]
            [stack-mitosis.predict :as predict]
            [stack-mitosis.request :as r]))

(defn aliased [prefix name]
  (str prefix "-" name))

(defn list-tree
  [instances root]
  (bfs-tree-seq (partial lookup/replicas instances) root))

(defn topo
  [instances ids]
  (->> (map #(set (lookup/replicas instances %)) ids)
       (zipmap ids)
       topological-sort))

;; postgres does not allow replica of replica, so need to promote before
;; replicating children
(defn copy-tree
  [instances source target transform]
  (let [df (map (comp transform (partial lookup/by-id instances))
                (list-tree instances target))]
    (conj (mapv #(op/create-replica (if-let [parent (:ReadReplicaSourceDBInstanceIdentifier %)]
                                      parent source)
                                    (:DBInstanceIdentifier %)) df)
          (op/promote (:DBInstanceIdentifier (first df))))))

(defn rename-tree
  [instances source transform]
  (let [tree (topo instances (list-tree instances source))]
    (map op/rename tree (map transform tree))))

(defn delete-tree
  [instances root]
  (->> (list-tree instances root)
       (topo instances)
       (map op/delete)))

(defn transform
  [prefix instance]
  (-> instance
      (update :DBInstanceIdentifier (partial aliased prefix))
      (update-if [:ReadReplicaSourceDBInstanceIdentifier] (partial aliased prefix))))

(defn replace-tree
  [instances source target]
  ;; actions in copy, rename & delete change the local instances db, so use
  ;; predict to update that db for calculating next set of operations by
  ;; applying computation thus far to the initial instances
  ;; TODO something something sequence monad
  (let [copy (copy-tree instances source target (partial transform "temp"))

        a (predict/state instances copy)
        rename-old (rename-tree a target (partial aliased "old"))

        b (predict/state instances (concat copy rename-old))
        rename-temp (rename-tree b (aliased "temp" target) #(str/replace % "temp-" ""))
        ;; re-deploy

        c (predict/state instances (concat copy rename-old rename-temp))
        delete (delete-tree c (aliased "old" target))]
    (concat copy rename-old rename-temp delete)))


(defn duplicate-instance [id]
  (format "Instance '%s' already exists" id))

(defn skippable [instances {:keys [op] :as action}]
  (cond
    (and (= op :CreateDBInstance)
         (lookup/by-id instances (r/db-id action)))
    (assoc action :skip (duplicate-instance (r/db-id action)))
    ;; catch error if replica has incorrect parent?
    (and (= op :CreateDBInstanceReadReplica)
         (lookup/by-id instances (r/db-id action)))
    (assoc action :skip (duplicate-instance (r/db-id action)))
    :else action))

(defn make-test-env []
  ;; mysql allows replicas of replicas, postgres does not
  (let [template {:DBInstanceClass "db.t3.micro"
                  :Engine "mysql"
                  :AllocatedStorage 5
                  :MasterUsername "root"}]
    ;; create & create replica of a fresh instance take ~6 minutes
    [(op/create (merge {:DBInstanceIdentifier "mitosis-root"
                        :MasterUserPassword (helpers/generate-password)}
                       template))
     (op/create (merge {:DBInstanceIdentifier "mitosis-alpha"
                        :MasterUserPassword (helpers/generate-password)}
                       template))
     (op/create-replica "mitosis-alpha" "mitosis-beta")
     #_(op/create-replica "mitosis-beta" "mitosis-gamma")
     ]))

(defn cleanup-test-env []
  (conj (delete-tree (predict/state [] (make-test-env)) "mitosis-alpha")
        (op/delete "mitosis-root")))
