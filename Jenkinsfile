pipeline {
    agent any

    // Don't build on Multibranch indexing (Jenkins restart / CasC job re-seed).
    // Manual "Build" and SCM webhooks still work.
    options {
        overrideIndexTriggers(false)
    }

    // Used on master (Build with Parameters). Ignored on feature/develop.
    parameters {
        choice(
            name: 'BUMP',
            choices: ['none', 'patch', 'minor', 'major'],
            description: 'Master only: none = Checkout + Maven (no release). patch/minor/major = release from current pom SNAPSHOT.'
        )
    }

    environment {
        DOCKER_IMAGE = 'adamko034/hello-world'
        GIT_CREDENTIALS_ID = 'github-pat'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
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

        stage('Master: skip release') {
            when {
                allOf {
                    branch 'master'
                    expression { releaseBump() == 'none' }
                }
            }
            steps {
                echo 'BUMP=none — skipping release/docker/QA/sync.'
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
            when {
                allOf {
                    branch 'master'
                    expression { releaseBump() != 'none' }
                }
            }
            steps {
                script {
                    def bump = releaseBump()
                    echo "Release bump: ${bump}"

                    def current = sh(
                        script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                        returnStdout: true
                    ).trim()

                    if (!current.endsWith('-SNAPSHOT')) {
                        error "Expected a SNAPSHOT version on master, got: ${current}"
                    }

                    def base = current.replace('-SNAPSHOT', '')
                    // patch: 0.0.4-SNAPSHOT → 0.0.4; minor/major bump base first
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
            when {
                allOf {
                    branch 'master'
                    expression { releaseBump() != 'none' }
                }
            }
            steps {
                sh 'mvn package -DskipTests'
            }
        }

        stage('Release: docker, tag, push') {
            when {
                allOf {
                    branch 'master'
                    expression { releaseBump() != 'none' }
                }
            }
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
            when {
                allOf {
                    branch 'master'
                    expression { releaseBump() != 'none' }
                }
            }
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
            when {
                allOf {
                    branch 'master'
                    expression { releaseBump() != 'none' }
                }
            }
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

def releaseBump() {
    def bump = params.BUMP?.trim()
    return bump in ['none', 'patch', 'minor', 'major'] ? bump : 'none'
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
