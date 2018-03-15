(ns commiteth.bounties
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [commiteth.common :refer [human-time
                                      display-data-page
                                      items-per-page
                                      issue-url]]
            [commiteth.handlers :as handlers]
            [commiteth.db :as db]
            [commiteth.ui-model :as ui-model]
            [commiteth.subscriptions :as subs]
            [commiteth.util :as util]))


(defn bounty-item [bounty]
  (let [{avatar-url   :repo_owner_avatar_url
         owner        :repo-owner
         repo-name    :repo-name
         issue-title  :issue-title
         issue-number :issue-number
         updated      :updated
         tokens       :tokens
         balance-eth  :balance-eth
         value-usd    :value-usd
         claim-count  :claim-count} bounty
        full-repo  (str owner "/" repo-name)
        repo-url   (str "https://github.com/" full-repo)
        repo-link  [:a {:href repo-url} full-repo]
        issue-link [:a
                    {:href (issue-url owner repo-name issue-number)}
                    issue-title]]
    [:div.open-bounty-item
     [:div.open-bounty-item-content
      [:div.header issue-link]
      [:div.bounty-item-row
       [:div.time (human-time updated)]
       [:span.bounty-repo-label repo-link]]

      [:div.footer-row
       [:div.balance-badge "ETH " balance-eth]
       (for [[tla balance] tokens]
         ^{:key (random-uuid)}
         [:div.balance-badge.token
          (str (subs (str tla) 1) " " balance)])
       [:span.usd-value-label "Value "] [:span.usd-balance-label (str "$" value-usd)]
       (when (> claim-count 0)
         [:span.open-claims-label (str claim-count " open claim"
                                       (when (> claim-count 1) "s"))])]]
     [:div.open-bounty-item-icon
      [:div.ui.tiny.circular.image
       [:img {:src avatar-url}]]]]))

(defn bounties-filter-tooltip-value-input-view [label tooltip-open? opts]
  [:div.open-bounties-filter-element-tooltip-value-input-container
   [:div.:input.open-bounties-filter-element-tooltip-value-input-label
    label]
   [:input.open-bounties-filter-element-tooltip-value-input
    {:type      "range"
     :min       (:min opts)
     :max       (:max opts)
     :step      (:step opts)
     :value     (:current-val opts)
     :on-change (when-let [f (:on-change-val opts)]
                  #(-> % .-target .-value int f))
     :on-mouse-up (when-let [f (:on-change-val opts)]
                    #(-> % .-target .-value int f))
     :on-focus  #(reset! tooltip-open? true)}]])

(defmulti bounties-filter-tooltip-view #(-> %2 ::ui-model/bounty-filter-type.category))

(defmethod bounties-filter-tooltip-view ::ui-model/bounty-filter-type-category|range
  [filter-type filter-type-def current-filter-value tooltip-open?]
  (let [default-min       (::ui-model/bounty-filter-type.min-val filter-type-def)
        default-max       (::ui-model/bounty-filter-type.max-val filter-type-def)
        common-range-opts {:min default-min :max default-max}
        current-min       (or (first current-filter-value) default-min)
        current-max       (or (second current-filter-value) default-max)
        on-change-fn      (fn [min-val max-val]
                            (rf/dispatch [::handlers/set-open-bounty-filter-type
                                          filter-type
                                          [(min min-val default-max)
                                           (max max-val default-min)]]))
        on-min-change-fn  (fn [new-min]
                            (let [new-max (max current-max (min default-max new-min))]
                              (on-change-fn new-min new-max)))
        on-max-change-fn  (fn [new-max]
                            (let [new-min (min current-min (max default-min new-max))]
                              (on-change-fn new-min new-max)))]
    [:div.open-bounties-filter-list
     (::ui-model/bounty-filter.type.header filter-type-def)
     [bounties-filter-tooltip-value-input-view "Min" tooltip-open? (merge common-range-opts
                                                                          {:current-val   current-min
                                                                           :on-change-val on-min-change-fn})]
     [bounties-filter-tooltip-value-input-view "Max" tooltip-open? (merge common-range-opts
                                                                          {:current-val   current-max
                                                                           :on-change-val on-max-change-fn})]]))

(defmethod bounties-filter-tooltip-view ::ui-model/bounty-filter-type-category|single-static-option
  [filter-type filter-type-def current-filter-value tooltip-open?]
  [:div.open-bounties-filter-list
   (for [[option-type option-text] (::ui-model/bounty-filter-type.options filter-type-def)]
     ^{:key (str option-type)}
     [:div.open-bounties-filter-list-option
      (merge {:on-click  #(do (rf/dispatch [::handlers/set-open-bounty-filter-type
                                            filter-type
                                            option-type])
                              (reset! tooltip-open? false))
              :tab-index 0
              :on-focus  #(reset! tooltip-open? true)}
             (when (= option-type current-filter-value)
               {:class "active"}))
      option-text])])

(defmethod bounties-filter-tooltip-view ::ui-model/bounty-filter-type-category|multiple-dynamic-options
  [filter-type filter-type-def current-filter-value tooltip-open?]
  (let [options (rf/subscribe [(::ui-model/bounty-filter-type.re-frame-subs-key-for-options filter-type-def)])]
    [:div.open-bounties-filter-list
     (for [option @options]
       (let [active? (boolean (and current-filter-value (current-filter-value option)))]
         ^{:key (str option)}
         [:div.open-bounties-filter-list-option-checkbox
          [:label
           {:on-click  #(rf/dispatch [::handlers/set-open-bounty-filter-type
                                      filter-type
                                      (cond
                                        (and active? (= #{option} current-filter-value)) nil
                                        active? (disj current-filter-value option)
                                        :else (into #{option} current-filter-value))])
            :tab-index 0
            :on-focus  #(do (.stopPropagation %) (reset! tooltip-open? true))}
           [:input
            {:type     "checkbox"
             :checked  active?
             :on-focus #(reset! tooltip-open? true)}]
           [:div.text option]]]))]))

(defn bounty-filter-view [filter-type current-filter-value]
  (let [tooltip-open? (r/atom false)]
    (fn [filter-type current-filter-value]
      [:div.open-bounties-filter-element-container
       {:tab-index 0
        :on-focus  #(reset! tooltip-open? true)
        :on-blur   #(reset! tooltip-open? false)}
       [:div.open-bounties-filter-element
        {:on-mouse-down #(swap! tooltip-open? not)
         :class         (when (or current-filter-value @tooltip-open?)
                          "open-bounties-filter-element-active")}
        [:div.text
         (if current-filter-value
           (ui-model/bounty-filter-value->short-text filter-type current-filter-value)
           (ui-model/bounty-filter-type->name filter-type))]
        (when current-filter-value
          [:div.remove-container
           {:tab-index 0
            :on-focus  #(.stopPropagation %)}
           [:img.remove
            {:src           "bounty-filter-remove.svg"
             :on-mouse-down (fn [e]
                              (.stopPropagation e)
                              (rf/dispatch [::handlers/set-open-bounty-filter-type filter-type nil])
                              (reset! tooltip-open? false))}]])]
       (when @tooltip-open?
         [:div.open-bounties-filter-element-tooltip
          [bounties-filter-tooltip-view
           filter-type
           (ui-model/bounty-filter-types-def filter-type)
           current-filter-value
           tooltip-open?]])])))

(defn bounty-filters-view []
  (let [current-filters (rf/subscribe [::subs/open-bounties-filters])]
    [:div.open-bounties-filter
     ; doall because derefs are not supported in lazy seqs: https://github.com/reagent-project/reagent/issues/18
     (doall
       (for [filter-type ui-model/bounty-filter-types]
         ^{:key (str filter-type)}
         [bounty-filter-view
          filter-type
          (get @current-filters filter-type)]))]))

(defn bounties-sort-view []
  (let [open? (r/atom false)]
    (fn []
      (let [current-sorting (rf/subscribe [::subs/open-bounties-sorting-type])]
        [:div.open-bounties-sort
         {:tab-index 0
          :on-blur   #(reset! open? false)}
         [:div.open-bounties-sort-element
          {:on-click #(swap! open? not)}
          (ui-model/bounty-sorting-type->name @current-sorting)
          [:div.icon-forward-white-box
           [:img
            {:src "icon-forward-white.svg"}]]]
         (when @open?
           [:div.open-bounties-sort-element-tooltip
            (for [sorting-type (keys ui-model/bounty-sorting-types-def)]
              ^{:key (str sorting-type)}
              [:div.open-bounties-sort-type
               {:on-click #(do
                             (reset! open? false)
                             (rf/dispatch [::handlers/set-open-bounties-sorting-type sorting-type]))}
               (ui-model/bounty-sorting-type->name sorting-type)])])]))))

(defn bounties-list [{:keys [items item-count page-number total-count]
                      :as   bounty-page-data}
                     container-element]
  (if (empty? items)
    [:div.view-no-data-container
     [:p "No matching bounties found."]]
    [:div
     (let [left  (inc (* (dec page-number) items-per-page))
           right (dec (+ left item-count))]
       [:div.item-counts-label-and-sorting-container
        [:div.item-counts-label
         [:span (str "Showing " left "-" right " of " total-count)]]
        [bounties-sort-view]])
     (display-data-page bounty-page-data bounty-item container-element)]))

(defn bounties-page []
  (let [bounty-page-data       (rf/subscribe [:open-bounties-page])
        open-bounties-loading? (rf/subscribe [:get-in [:open-bounties-loading?]])
        container-element      (atom nil)]
    (fn []
      (if @open-bounties-loading?
        [:div.view-loading-container
         [:div.ui.active.inverted.dimmer
          [:div.ui.text.loader.view-loading-label "Loading"]]]
        [:div.ui.container.open-bounties-container
         {:ref #(reset! container-element %1)}
         [:div.open-bounties-header "Bounties"]
         [:div.open-bounties-filter-and-sort
          [bounty-filters-view]]
         [bounties-list @bounty-page-data container-element]]))))
