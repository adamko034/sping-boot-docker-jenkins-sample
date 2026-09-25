pipeline {
    agent any

    environment {
        DOCKER_IMAGE = 'adamko034/hello-world'
        // Jenkins credential for pushing commits/tags to Git
        GIT_CREDENTIALS_ID = 'github-pat'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Maven build') {
            when {
                not { branch 'master' }
            }
            steps {
                sh 'mvn clean compile'
            }
        }

        stage('Maven test') {
            when {
                not { branch 'master' }
            }
            steps {
                sh 'mvn test'
            }
        }

        stage('Maven package') {
            when {
                not { branch 'master' }
            }
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

        stage('Release: choose bump') {
            when { branch 'master' }
            steps {
                script {
                    def bump = input(
                        message: 'Select release bump. patch = release current base (e.g. 0.0.1-SNAPSHOT → 0.0.1). minor/major bump the release version.',
                        parameters: [
                            choice(name: 'BUMP', choices: ['patch', 'minor', 'major'])
                        ]
                    )
                    env.BUMP = bump instanceof Map ? bump.BUMP : bump
                }
            }
        }

        stage('Release: versions, build, tag, push') {
            when { branch 'master' }
            steps {
                script {
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
                    def releaseVersion = (env.BUMP == 'patch') ? base : bumpSemVer(base, env.BUMP)
                    def nextSnapshot = bumpSemVer(releaseVersion, 'patch') + '-SNAPSHOT'

                    env.RELEASE_VERSION = releaseVersion
                    env.NEXT_SNAPSHOT = nextSnapshot
                    env.IMAGE_TAG = releaseVersion

                    echo "Current: ${current}"
                    echo "Release version: ${releaseVersion}"
                    echo "Next SNAPSHOT: ${nextSnapshot}"

                    // 1) pom → release version
                    sh "mvn -q versions:set -DnewVersion=${releaseVersion} -DgenerateBackupPoms=false"

                    // 2) build, test, package at release version
                    sh 'mvn clean compile'
                    sh 'mvn test'
                    sh 'mvn package -DskipTests'

                    // 3) docker build & push
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
