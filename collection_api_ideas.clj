;; Some collection api thoughts

;; CREATION



;; event types
#{:replace :create :update :reorder :remove}


;; have a collection -- update single element in collection. We don't want to trigger events that care about the entire collection => potentially need identifier. Can't just be index since not necessarily ordered


{:user {:children [{:name "Child 1"
                    :toys [{:name "Bear" :demon? false} {:name "Cow" :demon? false}]}
                   {:name "Child 2"
                    :toys []}
                   {:name "Child 3"
                    :toys [{:name "Cthulu" :demon? true} {:name "Pig" :demon? false} {:name "Ape" :demon? false}]}]}}

(core/transact ctx [[[:user :children] :dg/add {:name "Child 4"
                                                :toys []}]])
(core/transact ctx [[[:user :children 0 :toys]] :dg/add {:name "Thing"}])


;; default is :replace
{:model  [[:user {:id :user}
           [:children {:id          :children
                       :collection? true}
            [:toys {:id          :toys
                    :collection? true}]]]]

 :events [
          {:inputs       [[:children]]

           :handler      (fn [ctx [children] _]
                           )
           :interceptors [

                          {:dynamic-path [:dg/every :name]}
                          ]}

          {:context :toys
           :inputs  []
           :outputs []
           :handler (fn [_ _ _])}

          ;; need to know:
          ;; - position
          ;; - thing to insert (value)
          ;; - how to perform insertion (e.g. assoc, conj, cons, etc.)

          ;; gotchas
          ;; - modifying other collections given creation
          ;; - creating multiple things
          {:target  [:toys :dg/last]
           :handler (fn [ctx [toys]]
                      ;; toys => [{:colour red} ..]
                      )
           :type    :create}

          {:inputs  []
           :outputs []
           :handler (fn [_ _ _])
           :type    :update}
          ;; check for ordered collection on execution
          {:inputs  []
           :outputs []
           :handler (fn [_ _ _])
           :type    :reorder}
          {:inputs  []
           :outputs []
           :handler (fn [_ _ _])
           :type    :remove
           }]}

;; args: [action-kw coll-path & args]
;; create: [:create coll-path new-entity & [position]]
(core/transact-collection :create [[[:user :children 2]]] {:hello "world"})

;; UPDATES
;; Approach 1
{:model [[:user {:id :user}
          [:children {:id          :children
                      :collection? true}
           [:child {:id          :child
                    :collection? true}]]]]}

;; When we transact, we do so based off indexed traversal
(core/transact [[[:user :children 2 4] "Value"]])

;; Approach 2
{:model [[:user {:id :user}
          [:children {:id          :children
                      :collection? true
                      :id-fn       :id}
           [:child {:id          :child
                    :collection? true
                    :id-fn       :id}]]]]}

;; When we transact, we do so based off an identifier value
(core/transact [[[:user :children [{:id 1} {:id 2}] 2] {:id 3}]])

;; REORDERING



;; REMOVAL

;; args: [action-kw coll-path & args]
;; remove: [:remove coll-path]
(core/transact-collection :remove [[[:user :children 3]]] {:hello "world"})