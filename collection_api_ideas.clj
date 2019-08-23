
;; Some collection api thoughts

;; CREATION
{:model/model [[:user {:id :user}
                [:children {:id          :children
                            :collection? true}
                 [:child {:id          :child
                          :collection? true}]]]]}

;; args: [action-kw coll-path & args]
;; create: [:create coll-path new-entity & [position]]
(core/transact-collection :create [[[:user :children 2]]] {:hello "world"})

;; UPDATES
;; Approach 1
{:model/model [[:user {:id :user}
                [:children {:id          :children
                            :collection? true}
                 [:child {:id          :child
                          :collection? true}]]]]}

;; When we transact, we do so based off indexed traversal
(core/transact [[[:user :children 2 4] "Value"]])

;; Approach 2
{:model/model [[:user {:id :user}
                [:children {:id          :children
                            :collection? true
                            :id-fn       identity}
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