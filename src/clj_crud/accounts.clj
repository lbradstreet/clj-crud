(ns clj-crud.accounts
  (:require [clojure.tools.logging :refer [debug spy]]
            [clj-crud.system.email :as email]
            [clj-crud.util.layout :as l]
            [clj-crud.util.helpers :as h]
            [clj-crud.common :as c]
            [clj-crud.data.accounts :as accounts]
            [liberator.core :refer [resource defresource]]
            [liberator.representation :as lib-rep]
            [compojure.core :refer [defroutes ANY GET context]]
            [net.cgrand.enlive-html :as html]
            [cemerick.friend :as friend]
            [cemerick.friend.workflows :as workflows]))

(def signup-html (html/html-resource "templates/accounts/signup.html"))

(defn signup-layout [ctx]
  (c/emit-application
   ctx
   [:#content] (html/content signup-html)
   [:form#account-form] (html/do->
                         (html/set-attr :method "POST")
                         (html/set-attr :action "/signup"))
   [:div.account-panel]
   (let [{:keys [name email password password-repeat] :as account} (get-in ctx [:data :account])]
     (html/transform-content
      [:div#name] (l/maybe-error (get-in account [:errors :name]))
      [:div#name :input] (html/set-attr :value name)
      [:div#email] (l/maybe-error (get-in account [:errors :email]))
      [:div#email :input] (html/set-attr :value email)
      [:div#password] (l/maybe-error (get-in account [:errors :password]))
      [:div#password-repeat] (l/maybe-error (get-in account [:errors :password-repeat]))))))

(defresource signup
  :allowed-methods [:get :post]
  :available-media-types ["text/html"]
  :post! (fn [ctx]
           (let [{:keys [name email password password-repeat] :as params} (get-in ctx [:request :params])
                 errors (reduce merge {}
                                [(when (zero? (count name))
                                   [:name "Name can not be empty"])
                                 (when-not (accounts/valid-email? email)
                                   [:email "Invalid email"])
                                 (when (zero? (count password))
                                   [:password "Password can not be empty"])
                                 (when (not= password password-repeat)
                                   (let [msg "Passwords do not match"]
                                     {:password msg
                                      :password-repeat msg}))])
                 _ (debug "Params:" params "errors: " errors)
                 slug (accounts/slugify name)
                 create-account {:slug slug
                                 :name name
                                 :email email
                                 :password password}]
             (if (seq errors)
               {:account (merge create-account
                                {:errors errors})}
               (let [res (accounts/create-account (h/db ctx) create-account)]
                 (if-let [errors (:errors res)]
                   {:account (merge create-account
                                    res)}
                   {:account res})))))
  :post-redirect? (fn [ctx]
                    (not (get-in ctx [:account :errors])))
  :new? false ;; 201 created is useless here, want to redirect html flow
  :respond-with-entity? true
  :handle-see-other (fn [ctx]
                      (-> (h/location-flash (str "/profile/" (get-in ctx [:account :slug]))
                                            "Account created")
                          (friend/merge-authentication
                           (workflows/make-auth ((accounts/lookup-friend-identity (h/db ctx))
                                                 (get-in ctx [:account :name]))))))
  :handle-ok (fn [ctx]
               {:account (:account ctx)})
  :as-response (l/as-template-response signup-layout))

(def login-html (html/html-resource "templates/accounts/login.html"))

(defn login-layout [ctx]
  (c/emit-application
   ctx
   [:#content] (html/content login-html)
   [:form#login-form] (html/do->
                       (html/set-attr :method "POST")
                       (html/set-attr :action "/login"))
   [:div.login-panel]
   (let [username (get-in ctx [:request :params :username])
         failed (when (get-in ctx [:request :params :login_failed])
                  "Login failed")]
          (html/transform-content
           [:div#username] (l/maybe-error failed)
           [:div#username :input] (html/set-attr :value username)
           [:div#password] (l/maybe-error failed)
))))

(defresource login
  :allowed-methods [:get]
  :available-media-types ["text/html"]
  :as-response (fn [d ctx]
                 (if-let [identity (friend/identity (:request ctx))]
                   ;; succesful logins get redirected to /login, bring
                   ;; user to his own page from here
                   {:headers {"Location" (str "/profile/" (:current identity))}
                    :status 303}
                   ((l/as-template-response login-layout) d ctx))))

(defn logout [req]
  (friend/logout* {:headers {"Location" "/login"}
                   :flash "You are logged out"
                   :status 303}))

(def forgot-password-html (html/html-resource "templates/accounts/forgot_password.html"))

(defn forgot-password-layout [ctx]
  (c/emit-application
   ctx
   [:#content] (html/content forgot-password-html)
   [:form#forgot-password-form] (html/do->
                                 (html/set-attr :method "POST")
                                 (html/set-attr :action "/forgot-password"))
   [:div.forgot-password-panel]
   (let [{:keys [email] :as account} (get-in ctx [:data :account])]
          (html/transform-content
           [:div#email] (l/maybe-error (get-in account [:errors :email]))
           [:div#email :input] (html/set-attr :value email)))))

(defresource forgot-password
  :allowed-methods [:get :post]
  :available-media-types ["text/html"]
  :post! (fn [ctx]
           (let [email (get-in ctx [:request :params :email])
                 errors (reduce merge {}
                                [(when-not (accounts/valid-email? email)
                                   [:email "Invalid email"])])
                 account {:email email}]
             (if (seq errors)
               {:account (merge account {:errors errors})}
               (let [res (accounts/forgot-password (h/db ctx) email)]
                 (if-let [errors (:errors res)]
                   {:account (merge account
                                    res)}
                   (do
                     (debug "reset url is: " (str "http://localhost:3000/reset-password/" (:reset_token res)))
                     (let [emailer (get-in ctx [:request :emailer])]
                       (debug "FInd emailer:" ctx)
                       (email/send-email emailer email (str "http://localhost:3000/reset-password/" (:reset_token res))))
                     {:account res}))))))
  :post-redirect? (fn [ctx]
                    (not (get-in ctx [:account :errors])))
  :new? false ;; 201 created is useless here, want to redirect html flow
  :respond-with-entity? true
  :handle-see-other (fn [ctx]
                      (h/location-flash "/forgot-password"
                                        "Check your email for password reset instructions"))
  :handle-ok (fn [ctx]
               {:account (:account ctx)})
  :as-response (l/as-template-response forgot-password-layout))

(def reset-password-html (html/html-resource "templates/accounts/reset_password.html"))

(defn reset-password-layout [ctx]
  (c/emit-application
   ctx
   [:#content] (html/content reset-password-html)
   [:form#reset-password-form] (html/do->
                                 (html/set-attr :method "POST")
                                 (html/set-attr :action
                                                (str "/reset-password/" (get-in ctx [:request :params :reset_token]))))
   [:div.reset-password-panel]
   (let [{:keys [password password-repeat] :as account} (get-in ctx [:data :account])]
          (html/transform-content
           [:div#password] (l/maybe-error (get-in account [:errors :password]))
           [:div#password-repeat] (l/maybe-error (get-in account [:errors :password-repeat]))))))

(defresource reset-password
  :allowed-methods [:get :post]
  :available-media-types ["text/html"]
  :post! (fn [ctx]
           (let [{:keys [reset_token password password-repeat]} (get-in ctx [:request :params])
                 errors (reduce merge {}
                                [(when (zero? (count password))
                                   [:password "Password can not be empty"])
                                 (when (not= password password-repeat)
                                   (let [msg "Passwords do not match"]
                                     {:password msg
                                      :password-repeat msg}))])
                 account {:password password
                          :password-repeat password-repeat}]
             (if (seq errors)
               {:account (merge account {:errors errors})}
               (let [res (accounts/reset-password (h/db ctx) reset_token password)]
                 (if-let [errors (:errors res)]
                   {:account (merge account
                                    res)}
                   {:account res})))))
  :post-redirect? (fn [ctx]
                    (not (get-in ctx [:account :errors])))
  :new? false ;; 201 created is useless here, want to redirect html flow
  :respond-with-entity? true
  :handle-see-other (fn [ctx]
                      (h/location-flash "/login"
                                        "Login with your new password"))
  :handle-ok (fn [ctx]
               {:account (:account ctx)})
  :as-response (l/as-template-response reset-password-layout))


(defroutes accounts-routes
  (ANY "/signup" _ signup)
  (ANY "/login" _ login)
  (ANY "/logout" _ logout)
  (ANY "/forgot-password" _ forgot-password)
  (ANY "/reset-password/:reset_token" _ reset-password))
