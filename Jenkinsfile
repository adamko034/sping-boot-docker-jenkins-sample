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
                script {
                    // Parameterized bump only on master (each Multibranch job is per-branch).
                    // Choices are refreshed for the *next* Build with Parameters from current pom.
                    if (env.BRANCH_NAME == 'master') {
                        def current = sh(
                            script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                            returnStdout: true
                        ).trim()
                        def choices = bumpChoicesForSnapshot(current)
                        echo "Next release parameter choices (from ${current}): ${choices}"
                        properties([
                            parameters([
                                choice(
                                    name: 'BUMP',
                                    choices: choices,
                                    description: "Release version from current ${current}. Applies on the next master build."
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
