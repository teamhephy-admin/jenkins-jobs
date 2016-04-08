evaluate(new File("${WORKSPACE}/common.groovy"))

import utilities.StatusUpdater

[
  [type: 'master'],
  [type: 'pr'],
].each { Map config ->
  isMaster = config.type == 'master'
  isPR = config.type == 'pr'

  name = defaults.testJob[config.type]
  repoName = 'charts'

  testReportMsg = "Test Report: ${JENKINS_URL}job/${name}/\${BUILD_NUMBER}/testReport"
  upstreamJobMsg = "Upstream job: \${UPSTREAM_BUILD_URL}"

  job(name) {
    description """
      <p>Runs the <a href="https://github.com/deis/workflow-e2e">e2e tests</a> against a <a href="https://github.com/deis/charts/tree/master/${defaults.workflowChart}">${defaults.workflowChart}</a> chart</p>
    """.stripIndent().trim()

    scm {
      git {
        remote {
          github("deis/${repoName}")
          credentials('597819a0-b0b9-4974-a79b-3a5c2322606d')
        }
        branch('master')
      }
    }

    logRotator {
      numToKeep defaults.numBuildsToKeep
    }

    if (isPR) {
      concurrentBuild()
      throttleConcurrentBuilds {
        maxPerNode(defaults.maxBuildsPerNode)
        maxTotal(defaults.maxTotalConcurrentBuilds)
      }
    }

    publishers {
      slackNotifications {
        // TODO: re-enable once integrationToken can make use of Jenkins'
        // secure credentials handling:
        // https://github.com/jenkinsci/slack-plugin/pull/208
        // teamDomain(defaults.slack['teamDomain'])
        // integrationToken('${SLACK_INTEGRATION_TOKEN}')
        // projectChannel('#${UPSTREAM_SLACK_CHANNEL}')
        customMessage([testReportMsg, upstreamJobMsg].join('\n'))
        notifyAborted()
        notifyFailure()
        notifySuccess()
        showCommitList()
        includeTestSummary()
       }

       if (isMaster) {
         git {
           pushOnlyIfSuccess()
           branch('origin', 'master')
         }
       }

       archiveJunit('logs/**/junit*.xml') {
         retainLongStdout(false)
       }

       archiveArtifacts {
         pattern('logs/${BUILD_NUMBER}/**')
         onlyIfSuccessful(false)
         fingerprint(false)
       }

       if (isPR) {
         def statuses = [['SUCCESS', 'success'],['FAILURE', 'failure'],['ABORTED', 'error']]
         postBuildScripts {
           onlyIfBuildSucceeds(false)
           steps {
             statuses.each { buildStatus, commitStatus ->
               conditionalSteps {
                 condition {
                   status(buildStatus, buildStatus)
                   steps {
                     shell StatusUpdater.updateStatus(
                       buildStatus: buildStatus, commitStatus: commitStatus, jobName: name, repoName: '${COMPONENT_REPO}', commitSHA: '${COMPONENT_COMMIT}')
                   }
                 }
               }
             }
           }
         }
       }
     }

    parameters {
      // TODO: remove these <COMPONENT>_SHA env vars to use the canonical
      // COMPONENT_COMMIT.  Requires change in the chart-mate repo.
      repos.each { Map repo ->
        stringParam(repo.commitEnvVar, '', "${repo.name} commit SHA")
      }
     stringParam('COMPONENT_REPO', '', "Component repo name")
     stringParam('COMPONENT_COMMIT', '', "Component commit SHA")
    }

    triggers {
      cron('@daily')
      if (isMaster) {
        githubPush()
      }
    }

    wrappers {
      timeout {
        absolute(25)
        failBuild()
      }
      timestamps()
      colorizeOutput 'xterm'
      credentialsBinding {
        string("GCLOUD_CREDENTIALS", "246d6550-569b-4925-8cda-e11a4f0d6803")
        string("GITHUB_ACCESS_TOKEN", "8e11254f-44f3-4ddd-bf98-2cabcb7434cd")
      }
    }

    environmentVariables {
      env('COMMIT', isMaster)
      env('PARALLEL_TESTS', true)
    }

    steps {
      shell """
        #!/usr/bin/env bash

        set -eo pipefail

        ./ci.sh
        if [ "\${COMMIT}" == "true" ]; then
          ${defaults.bumpverCommitCmd}
        fi
      """.stripIndent().trim()
    }
  }
}
