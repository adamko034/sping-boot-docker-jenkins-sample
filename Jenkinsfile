pipeline {
    agent any

    // Required for Multibranch: Declarative parameters make "Build with Parameters"
    // appear on the branch job after the first run of that branch.
    parameters {
        choice(
            name: 'BUMP',
            choices: ['patch', 'minor', 'major'],
            description: 'Release bump (used only on master).'
        )
    }

    environment {
        DOCKER_IMAGE = 'adamko034/hello-world'
        // Jenkins credential for pushing commits/tags to Git
        GIT_CREDENTIALS_ID = 'github-pat'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // Refresh master choices with concrete versions for the *next* build.
                    if (env.BRANCH_NAME == 'master') {
                        def current = sh(
                            script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                            returnStdout: true
                        ).trim()
                        def choices = bumpChoicesForSnapshot(current)
                        echo "BUMP param for next master build (from ${current}): ${choices}"
                        properties([
                            parameters([
                                choice(
                                    name: 'BUMP',
                                    choices: choices,
                                    description: "Release version from current ${current}."
                                )
                            ])
                        ])
                    }
                }
            }
        }

        stage('Maven build') {
            steps {
                sh 'mvn clean compile'
            }
        }

        stage('Maven test') {
            steps {
                sh 'mvn test'
            }
        }

        stage('Maven package') {
            steps {
                sh 'mvn package -DskipTests'
            }
        }

        stage('Feature branch done') {
            when {
                allOf {
                    not { branch 'develop' }
                    not { branch 'master' }
                }
            }
            steps {
                echo "Feature branch ${env.BRANCH_NAME}: Maven only — no Docker/deploy."
            }
        }

        stage('Develop: Docker build & push') {
            when { branch 'develop' }
            steps {
                script {
                    env.IMAGE_TAG = sh(
                        script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                        returnStdout: true
                    ).trim()
                    echo "Develop image tag: ${env.IMAGE_TAG}"

                    docker.withRegistry('', 'dockerhub-cred') {
                        def image = docker.build("${env.DOCKER_IMAGE}:${env.IMAGE_TAG}")
                        image.push()
                    }
                }
            }
        }

        stage('Develop: Deploy DEV') {
            when { branch 'develop' }
            environment {
                KUBECONFIG = credentials('minikube-kubeconfig')
            }
            steps {
                sh '''
                    helm upgrade --install hello-world ./helm/hello-world \
                      -n hello-world-dev \
                      -f ./helm/hello-world/values-dev.yaml \
                      --set image.repository=${DOCKER_IMAGE} \
                      --set image.tag=${IMAGE_TAG} \
                      --create-namespace
                '''
            }
        }

        stage('Release: set version') {
            when { branch 'master' }
            steps {
                script {
                    def bump = bumpTypeFromParam(params.BUMP)
                    echo "Release bump: ${bump} (param=${params.BUMP})"

                    def current = sh(
                        script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                        returnStdout: true
                    ).trim()

                    if (!current.endsWith('-SNAPSHOT')) {
                        error "Expected a SNAPSHOT version on master, got: ${current}"
                    }

                    def base = current.replace('-SNAPSHOT', '')
                    // patch: 0.0.1-SNAPSHOT → release 0.0.1, next 0.0.2-SNAPSHOT
                    // minor/major: bump base for release, then +patch for next SNAPSHOT
                    def releaseVersion = (bump == 'patch') ? base : bumpSemVer(base, bump)
                    def nextSnapshot = bumpSemVer(releaseVersion, 'patch') + '-SNAPSHOT'

                    env.RELEASE_VERSION = releaseVersion
                    env.NEXT_SNAPSHOT = nextSnapshot
                    env.IMAGE_TAG = releaseVersion

                    echo "Current: ${current}"
                    echo "Release version: ${releaseVersion}"
                    echo "Next SNAPSHOT: ${nextSnapshot}"

                    sh "mvn -q versions:set -DnewVersion=${releaseVersion} -DgenerateBackupPoms=false"
                }
            }
        }

        stage('Release: package') {
            when { branch 'master' }
            steps {
                // Re-package so the jar matches the release version (tests already passed above)
                sh 'mvn package -DskipTests'
            }
        }

        stage('Release: docker, tag, push') {
            when { branch 'master' }
            steps {
                script {
                    docker.withRegistry('', 'dockerhub-cred') {
                        def image = docker.build("${env.DOCKER_IMAGE}:${env.IMAGE_TAG}")
                        image.push()
                    }

                    sh """
                        git config user.email 'jenkins@local'
                        git config user.name 'Jenkins'
                        git add pom.xml
                        git commit -m "Release ${env.RELEASE_VERSION}" || true
                        git tag -a ${env.RELEASE_VERSION} -m "Release ${env.RELEASE_VERSION}"
                    """

                    sh "mvn -q versions:set -DnewVersion=${env.NEXT_SNAPSHOT} -DgenerateBackupPoms=false"

                    sh """
                        git add pom.xml
                        git commit -m "Prepare next development version ${env.NEXT_SNAPSHOT}"
                    """

                    withCredentials([usernamePassword(
                        credentialsId: env.GIT_CREDENTIALS_ID,
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_PASS'
                    )]) {
                        sh '''
                            set +x
                            REMOTE_PATH=$(git config --get remote.origin.url | sed -E 's#https?://##' | sed -E 's#git@([^:]+):#\\1/#')
                            git push "https://${GIT_USER}:${GIT_PASS}@${REMOTE_PATH}" HEAD:master
                            git push "https://${GIT_USER}:${GIT_PASS}@${REMOTE_PATH}" "${RELEASE_VERSION}"
                        '''
                    }
                }
            }
        }

        stage('Release: Deploy QA') {
            when { branch 'master' }
            environment {
                KUBECONFIG = credentials('minikube-kubeconfig')
            }
            steps {
                sh '''
                    helm upgrade --install hello-world ./helm/hello-world \
                      -n hello-world-qa \
                      -f ./helm/hello-world/values-qa.yaml \
                      --set image.repository=${DOCKER_IMAGE} \
                      --set image.tag=${IMAGE_TAG} \
                      --create-namespace
                '''
            }
        }

        stage('Release: sync master → develop') {
            when { branch 'master' }
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: env.GIT_CREDENTIALS_ID,
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_PASS'
                    )]) {
                        def synced = sh(
                            script: '''
                                set +x
                                REMOTE_PATH=$(git config --get remote.origin.url | sed -E 's#https?://##' | sed -E 's#git@([^:]+):#\\1/#')
                                AUTH_URL="https://${GIT_USER}:${GIT_PASS}@${REMOTE_PATH}"

                                git config user.email 'jenkins@local'
                                git config user.name 'Jenkins'

                                git fetch "${AUTH_URL}" +refs/heads/master:refs/remotes/origin/master \
                                                      +refs/heads/develop:refs/remotes/origin/develop

                                git checkout -B develop origin/develop

                                set +e
                                git merge origin/master -m "Merge master into develop after release ${RELEASE_VERSION}"
                                MERGE_STATUS=$?
                                set -e

                                if [ "$MERGE_STATUS" -ne 0 ]; then
                                    git merge --abort 2>/dev/null || true
                                    echo "ERROR: Conflict merging master into develop after release ${RELEASE_VERSION}."
                                    echo "Resolve manually: checkout develop, merge master, fix conflicts (usually pom.xml), push develop."
                                    exit 1
                                fi

                                git push "${AUTH_URL}" HEAD:develop
                            ''',
                            returnStatus: true
                        )
                        if (synced != 0) {
                            error "Failed to sync master → develop after release ${env.RELEASE_VERSION}. Resolve merge conflicts on develop manually."
                        }
                        echo "Synced master → develop after release ${env.RELEASE_VERSION}"
                    }
                }
            }
        }
    }
}

def bumpSemVer(String version, String bumpType) {
    def parts = version.tokenize('.').collect { it as int }
    while (parts.size() < 3) {
        parts << 0
    }
    if (bumpType == 'major') {
        parts[0]++
        parts[1] = 0
        parts[2] = 0
    } else if (bumpType == 'minor') {
        parts[1]++
        parts[2] = 0
    } else {
        parts[2]++
    }
    return parts.join('.')
}

/** Choice labels for Build with Parameters, e.g. patch (0.0.1), minor (0.1.0), major (1.0.0). */
def bumpChoicesForSnapshot(String current) {
    def base = current.replace('-SNAPSHOT', '')
    return [
        "patch (${base})",
        "minor (${bumpSemVer(base, 'minor')})",
        "major (${bumpSemVer(base, 'major')})"
    ]
}

/** Accepts "patch", "minor", "major", or "patch (0.0.1)". */
def bumpTypeFromParam(String bumpParam) {
    if (!bumpParam) {
        return 'patch'
    }
    def type = bumpParam.trim().tokenize(' ')[0]
    return type in ['patch', 'minor', 'major'] ? type : 'patch'
}
