#!/usr/bin/env groovy

node('linux') {
checkout scm

def dockerreponame = "statusim/openbounty-app"

	try {
		stage('Build & push') {

			GIT_COMMIT_HASH = sh (script: "git rev-parse --short HEAD | tr -d '\n'", returnStdout: true)

			docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-statusvan') {
				def openbountyApp = docker.build("${dockerreponame}:${env.BUILD_NUMBER}")
				openbountyApp.push("${env.BRANCH_NAME}")
		        if (env.BRANCH_NAME == 'develop') {
		            openbountyApp.push("${dockerreponame}:develop")
		        } else if (env.BRANCH_NAME == 'master') {
		            openbountyApp.push("${dockerreponame}:master")
		        } else {
		            println "Not named branch have no custom tag"
		        }
			}

		}

		stage('Deploy') {
			build job: 'status-openbounty/openbounty-cluster', parameters: [[$class: 'StringParameterValue', name: 'DEPLOY_ENVIRONMENT', value: "dev"], [$class: 'StringParameterValue', name: 'BRANCH', value: env.BRANCH_NAME]]
		}

	} catch (e) {
    // slackSend color: 'bad', message: REPO + ":" + BRANCH_NAME + ' failed to build. ' + env.BUILD_URL
    throw e
  }
}