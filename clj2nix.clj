(ns clj2nix
  (:require [clojure.tools.deps.alpha :refer [resolve-deps]]
            [clojure.tools.deps.alpha.util.maven :as mvn]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))


(def autogenerate-comment "# generated by clj2nix\n")

(def prefix "{ pkgs, ... }:
  
  let repos = [
        \"https://repo.clojars.org/\"
        \"https://repo1.maven.org/\"
        \"http://central.maven.org/maven2/\"
        \"http://oss.sonatype.org/content/repositories/releases/\"
        \"http://oss.sonatype.org/content/repositories/public/\"
        \"http://repo.typesafe.com/typesafe/releases/\"
      ];

  in rec {
      makePaths = {extraClasspaths ? []}: (builtins.map (dep: if builtins.hasAttr \"jar\" dep.path then dep.path.jar else dep.path) packages) ++ extraClasspaths;
      makeClasspaths = {extraClasspaths ? []}: builtins.concatStringsSep \":\" (makePaths {inherit extraClasspaths;});

      packages = [")

(def suffix
  "
  ];
  }
  ")

(defn maven-item [name artifactID groupID sha512 version]
  (format "
  {
    name = \"%s\";
    path = pkgs.fetchMavenArtifact {
      inherit repos;
      artifactId = \"%s\";
      groupId = \"%s\";
      sha512 = \"%s\";
      version = \"%s\";
    };
  }
" (str name) artifactID groupID sha512 (str version)))

(defn git-item [name artifactID url rev sha256]
  (format "
  {
    name = \"%s\";
    path = pkgs.fetchgit {
      name = \"%s\";
      url = \"%s\";
      rev = \"%s\";
      sha256 = \"%s\";
    };
  }
" (str name) artifactID url rev sha256))


(defn resolve-artifact-and-group [name]
  (let [split (string/split (str name) #"/")]
    (if (= 1 (count split))
      [name name]
      [(first split) (second split)])))

(defn resolve-git-sha256 [git-url rev]
  (let [unpack? (or (re-find #"\.tar.gz$" git-url)
                    (re-find #"\.zip$" git-url)
                    (re-find #"\.tar$" git-url))
        result  (:out (sh "nix-prefetch-git"
                          "--url" git-url
                          "--rev" rev))]
    (get (json/read-str result) "sha256")))

(defn resolve-sha512 [filepath]
  (assert (.exists (io/as-file filepath))
          (str filepath " " "doesn't exists."))
  (subs (:out (sh "sha512sum" filepath)) 0 128))


(defn generate-items [deps]
  (->> (seq deps)
       (reduce (fn [acc [name dep]]
                 (let [[groupID artifactID] (resolve-artifact-and-group name)]
                   (let [git-dep?   (contains? dep :git/url)
                         local-dep? (contains? dep :local/root)]
                     (assert (contains? dep :paths)
                             (str name " has not been fetched locally."
                                  " Make sure that all dependencies exist locally."))
                     (cond
                       local-dep? (do (println "Warning: " name
                                               " is a local dependency."
                                               " All its remote dependencies will "
                                               " be resolved, but you need to add the "
                                               " jar/dir manually as a source to your "
                                               " nix-expression for it to be included."
                                               " As well as append its classpath via the "
                                               " argument extraClasspaths in "
                                               " makeClasspaths if needed.")
                                      acc)
                       git-dep?   (conj
                                   acc
                                   (git-item name
                                             artifactID
                                             (:git/url dep)
                                             (:sha dep)
                                             (resolve-git-sha256 (:git/url dep) (:sha dep))))
                       :else      (conj
                                   acc
                                   (maven-item name
                                               artifactID
                                               groupID
                                               (resolve-sha512 (first (:paths dep)))
                                               (:mvn/version dep))))))) [])
       (apply str)))

(defn generate-nix-expr [deps]
  (str autogenerate-comment
       prefix
       (generate-items deps)
       suffix))

(defn -main [deps-edn-path output-path]
  (spit
   output-path
   (generate-nix-expr
    (resolve-deps
     (merge
      (edn/read-string
       (slurp deps-edn-path))
      {:mvn/repos mvn/standard-repos})
     nil)))
  (System/exit 0))



(comment 
  (do (spit "debug.nix"
            (generate-nix-expr
             (resolve-deps
              '{:deps
                {org.clojure/clojure        {:mvn/version "1.9.0"}
                 org.clojure/clojurescript  {:mvn/version "1.10.238"}
                 org.clojure/tools.reader   {:mvn/version "1.3.0-alpha3"}
                 com.cognitect/transit-cljs {:mvn/version "0.8.256"}
                 malabarba/lazy-map         {:mvn/version "1.3"}
                 fipp                       {:mvn/version "0.6.12"}
                 clj-time                   {:mvn/version "0.14.2"}}}
              nil
              )))
      (System/exit 0)))

