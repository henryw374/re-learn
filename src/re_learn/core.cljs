(ns re-learn.core
  (:require [re-frame.core :as re-frame]
            [reagent.core :as r]
            [re-frame.std-interceptors :refer [trim-v]]
            [re-learn.local-storage :as local-storage]
            [cljs.reader :as edn]
            [dommy.core :as dom]))

(re-frame/reg-event-fx ::init
                       [(re-frame/inject-cofx ::local-storage/load :re-learn/lessons-learned)]
                       (fn [{:keys [db local-storage]}]
                         {:db (assoc db ::lessons-learned (:re-learn/lessons-learned local-storage))}))

(re-frame/reg-event-fx ::hard-reset
                       (fn [{:keys [db]}]
                         {:db (assoc db ::lessons-learned {})
                          ::local-storage/save [:re-learn/lessons-learned {}]}))

(re-frame/reg-fx ::on-dom-event
                 (fn [[action selector on-event]]
                   (dom/listen-once! (dom/sel1 selector) action on-event)))

(def ^:private lesson-defaults {:position :right
                                :version 1})

(defn- add-lesson [lessons {:keys [id] :as lesson}]
  (assoc lessons id (merge lesson-defaults lesson)))

(defn- add-lessons [lessons new-lessons]
  (reduce add-lesson lessons new-lessons))

(defn- ->lesson-id [lesson]
  (cond
    (keyword? lesson)
    lesson

    (instance? MetaFn lesson)
    (::lesson-id (meta lesson))

    (map? lesson)
    (:id lesson)

    :else
    lesson))

(re-frame/reg-event-db ::register-lesson [trim-v]
                       (fn [db [{:keys [id] :as lesson}]]
                         (update db ::lessons add-lesson lesson)))

(re-frame/reg-event-db ::deregister-lesson [trim-v]
                       (fn [db [lesson-id]]
                         (update db ::lessons dissoc lesson-id)))

(re-frame/reg-event-fx ::lesson-learned [trim-v]
                       (fn [{:keys [db]} [lesson-id]]
                         (let [{:keys [version]} (get-in db [::lessons lesson-id])
                               lessons-learned (assoc (::lessons-learned db) lesson-id version)]
                           {:db (assoc db ::lessons-learned lessons-learned)
                            ::local-storage/save [:re-learn/lessons-learned lessons-learned]})))

(re-frame/reg-event-db ::register-tutorial [trim-v]
                       (fn [db [{:keys [id lessons] :as tutorial}]]
                         (let [inline-lessons (filter map? lessons)]
                           (-> db
                               (update ::lessons add-lessons inline-lessons)
                               (update ::tutorials assoc id (update tutorial :lessons #(map ->lesson-id %)))))))

(re-frame/reg-event-db ::deregister-tutorial [trim-v]
                       (fn [db [tutorial-id]]
                         (update db ::tutorials dissoc tutorial-id)))

(re-frame/reg-event-fx ::prepare-lesson [trim-v]
                       (fn [{:keys [db]} [lesson-id]]
                         (when-let [continue (get-in db [::lessons lesson-id :continue])]
                           {::on-dom-event [:click continue #(re-frame/dispatch [::lesson-learned lesson-id])]})))

(defn register-lesson [lesson]
  (fn [this] (re-frame/dispatch [::register-lesson (assoc lesson :dom-node (r/dom-node this))])))

(defn deregister-lesson [lesson-id]
  (fn [_] (re-frame/dispatch [::deregister-lesson lesson-id])))

(defn register-tutorial [tutorial]
  (fn [this] (re-frame/dispatch [::register-tutorial tutorial])))

(defn deregister-tutorial [tutorial-id]
  (fn [_] (re-frame/dispatch [::deregister-tutorial tutorial-id])))

(defn- already-learned?
  ([lessons-learned]
   (fn [{:keys [id version]}]
     (and (contains? lessons-learned id)
          (<= version (get lessons-learned id)))))
  ([lessons-learned lesson]
   ((already-learned? lessons-learned) lesson)))

(re-frame/reg-sub
 ::current-lesson
 (fn [db]
   (->> (::lessons db)
        vals
        (remove (already-learned? (::lessons-learned db)))
        first)))

(re-frame/reg-sub
 ::current-tutorial
 (fn [db]
   (first (for [{:keys [lessons] :as tutorial} (vals (::tutorials db))
                lesson (keep (::lessons db) lessons)
                :when (not (already-learned? (::lessons-learned db) lesson))]
            lesson))))

(defn init []
  (re-frame/dispatch-sync [::init]))

(defn reset-education! []
  (re-frame/dispatch [::hard-reset]))