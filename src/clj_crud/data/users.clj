(ns clj-crud.data.users
  (:require [clojure.tools.logging :refer [info debug spy]]
            [clojure.string :as string]
            [clojure.java.jdbc :as jdbc]))

(defn create-entry [db data]
  (let [res (jdbc/insert! db :entries {:data (pr-str data)
                                       :updated_at (.getTime (java.util.Date.))})]
    (-> res first :1)))

(defn update-entry [e new]
  [(merge {} (when-not (seq (:data new))
               {:data "Data cannot be empty"}))
   (merge e
          (select-keys new (keys e)))])

(defn put-entry [db entry]
  (do (jdbc/update! db :entries (assoc (select-keys entry [:data])
                                  :updated_at (.getTime (java.util.Date.)))
                    ["id = ?" (:id entry)])
      nil))

(def slug-characters
  (let [ab "abcdefghijklmnopqrstuvwxyz"
        nums "0123456789"
        syms "-_"]
    (-> #{}
        (into ab)
        (into (string/capitalize ab))
        (into nums)
        (into syms))))

(defn slugify [name]
  (let [name (str name)]
    (-> name
        str
        string/lower-case
        (->>
         (filter slug-characters)
         (apply str)))))

(defn get-user [db slug]
  (first (jdbc/query db ["SELECT * FROM users WHERE slug = ?" slug])))

(defn update-user [db user]
  (do (let [res (jdbc/update! db :users (assoc user
                                          :updated_at (.getTime (java.util.Date.)))
                              ["slug = ?" (:slug user)])]
        (when-not (= res [1])
          (throw (Exception.  "DB Update has not succeeded"))))
      nil))

(defn create-user [db user]
  (debug "hello world!")

  (let [exists-by-slug (get-user db (:slug user))]
    (if exists-by-slug
      ;; user needs to select different name for a unique slug
      {:errors {:name "Name already taken"}}
      (let [now (.getTime (java.util.Date.))
            res (jdbc/insert! db :users {:slug (:slug user)
                                         :name (:name user)
                                         :created_at now
                                         :updated_at now})]
        (when-not (contains? (first res) :1)
          (throw (Exception.  "DB Create has not succeeded")))
        (get-user db (:slug user))))))

(defn users-list [db]
  (jdbc/query db ["SELECT * FROM users"]))
