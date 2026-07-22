(ns metabase.driver.altertable.workflow-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all])
  (:import
   (com.fasterxml.jackson.databind ObjectMapper)))

(defn- project-file
  [path]
  (io/file (System/getProperty "user.dir") path))

(defn- file-contents
  [path]
  (let [file (project-file path)]
    (is (.isFile file) (str path " should exist"))
    (when (.isFile file)
      (slurp file))))

(deftest pull-request-ci-contract
  (when-let [workflow (file-contents ".github/workflows/ci.yml")]
    (is (re-find #"(?m)^  pull_request:" workflow))
    (is (re-find #"(?m)^      - main$" workflow))
    (is (re-find #"ghcr\.io/altertable-ai/altertable-mock@sha256:[0-9a-f]{64}" workflow))
    (is (re-find #"ALTERTABLE_MOCK_USERS: testuser:testpass" workflow))
    (is (re-find #"clojure -X:test" workflow))))

(deftest release-workflow-contract
  (when-let [workflow (file-contents ".github/workflows/release-please.yml")]
    (is (re-find #"googleapis/release-please-action@[0-9a-f]{40}" workflow))
    (is (re-find #"workflow_dispatch:" workflow))
    (is (re-find #"gh release view" workflow))
    (is (re-find #"ref: refs/tags/\$\{\{ env\.RELEASE_TAG \}\}" workflow))
    (is (re-find #"ref: 0c64e27763e13123766434979ed3e41f0cd185bc" workflow))
    (is (re-find #"METABASE_DIR=.*bin/build-driver\.sh" workflow))
    (is (re-find #"gh release upload" workflow))
    (is (re-find #"altertable\.metabase-driver\.jar" workflow))
    (is (re-find #"(?m)^\s+cd target$" workflow))
    (is (re-find #"sha256sum altertable\.metabase-driver\.jar" workflow))))

(deftest release-please-configuration-contract
  (let [config-file   (project-file "release-please-config.json")
        manifest-file (project-file ".release-please-manifest.json")]
    (is (.isFile config-file) "release-please-config.json should exist")
    (is (.isFile manifest-file) ".release-please-manifest.json should exist")
    (when (and (.isFile config-file) (.isFile manifest-file))
      (let [mapper   (ObjectMapper.)
            config   (.readValue mapper config-file java.util.Map)
            package  (get (get config "packages") ".")
            manifest (.readValue mapper manifest-file java.util.Map)]
        (is (= "simple" (get package "release-type")))
        (is (= "0.1.0" (get package "initial-version")))
        (is (= [{"type"     "yaml"
                 "path"     "resources/metabase-plugin.yaml"
                 "jsonpath" "$.info.version"}]
               (get package "extra-files")))
        (is (empty? manifest))))))

(deftest driver-build-script-contract
  (let [script-file (project-file "bin/build-driver.sh")]
    (is (.isFile script-file) "bin/build-driver.sh should exist")
    (when (.isFile script-file)
      (let [script (slurp script-file)]
        (is (.canExecute script-file) "bin/build-driver.sh should be executable")
        (is (re-find #"METABASE_DIR" script))
        (is (re-find #"build-drivers\.build-driver/build-driver!" script))
        (is (re-find #":driver :altertable" script))))))
