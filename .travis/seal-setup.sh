# Sealed build environment setup, sourced by .travis.yml (before_install) and by
# .github/workflows/seal-build.yml. Do not use `set -e`: this file is sourced.
#
# 1. Zulu JDK 7: the enforcer requires JDK >= 1.7 and the published jar was built
#    with JDK 1.7.0_25 (Build-Jdk header). Neither Travis nor setup-java provision
#    JDK 7 anymore, so it is downloaded from Azul. Its bundled cacerts predate the
#    current Maven Central chain, so the cacerts of the JDK already on the runner
#    (8+) are copied in.
# 2. Maven 3.8.8 (runs on Java 7, TLSv1.2 forced for Maven Central).
# 3. .travis/seal-java.security: the securechat example keystore used by the
#    *SocketSslEchoTest tests holds a 512-bit RSA certificate, which 7u352's
#    default jdk.certpath/jdk.tls.disabledAlgorithms reject. Surefire runs with
#    forkMode=never, so the override in MAVEN_OPTS reaches the tests.
SEAL_JDK=${SEAL_JDK:-zulu7.56.0.11-ca-jdk7.0.352-linux_x64}
SEAL_MAVEN=${SEAL_MAVEN:-3.8.8}
SEAL_ROOT="$(pwd)"

SEAL_DONOR="${JAVA_HOME:-}"
if [ -z "$SEAL_DONOR" ] || [ ! -d "$SEAL_DONOR" ]; then
  SEAL_DONOR="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
fi
SEAL_CACERTS=""
for c in "$SEAL_DONOR/jre/lib/security/cacerts" "$SEAL_DONOR/lib/security/cacerts" "$SEAL_DONOR/../lib/security/cacerts"; do
  if [ -f "$c" ]; then SEAL_CACERTS="$c"; break; fi
done

mkdir -p /tmp/seal-jdk7 || return 1
curl -fsSL --retry 5 --retry-delay 10 -o /tmp/seal-jdk7.tar.gz "https://cdn.azul.com/zulu/bin/$SEAL_JDK.tar.gz" || return 1
tar -xzf /tmp/seal-jdk7.tar.gz -C /tmp/seal-jdk7 --strip-components=1 || return 1
if [ -n "$SEAL_CACERTS" ]; then
  cp -f "$SEAL_CACERTS" /tmp/seal-jdk7/jre/lib/security/cacerts || return 1
fi
export JAVA_HOME=/tmp/seal-jdk7
export PATH="$JAVA_HOME/bin:$PATH"

curl -fsSL --retry 5 --retry-delay 10 -o /tmp/seal-maven.tar.gz \
  "https://archive.apache.org/dist/maven/maven-3/$SEAL_MAVEN/binaries/apache-maven-$SEAL_MAVEN-bin.tar.gz" || return 1
tar -xzf /tmp/seal-maven.tar.gz -C /tmp || return 1
export M2_HOME="/tmp/apache-maven-$SEAL_MAVEN"
export PATH="$M2_HOME/bin:$PATH"
export MAVEN_OPTS="-Dhttps.protocols=TLSv1.2 -Djava.security.properties=$SEAL_ROOT/.travis/seal-java.security"

java -version
mvn -version
