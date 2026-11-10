# chat-ledger image (task 13, D1). One image, three commands: the web process (default), `demo-refresh ...` and `db-migrate`.
# The compose file (deploy/compose.yaml) sets the JVM options and the command of each container.
#
# Both bases are pinned by digest (looked up during task 13 with `docker buildx imagetools inspect`):
#   build:   maven:3.9-eclipse-temurin-21        JDK 21.0.12.1+1
#   runtime: gcr.io/distroless/java21-debian13:nonroot   Temurin 21.0.13+9 (amd64 and arm64), Debian 13, no shell, no package manager, uid 65532
#            (was java21-debian12: its newest digest carried fixable HIGH/CRITICAL Debian findings; task 13, register 152)
# Both are Java 21: the build JDK compiles for 21, the runtime runs 21.
# No secret is passed to the build: there is no ARG and no secret mount. CI checks the layers (deploy/check-image.sh).

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
# The POMs first: dependencies resolve in their own cached layer.
COPY pom.xml ./
COPY ledger-core/pom.xml ledger-core/
COPY extraction/pom.xml extraction/
COPY ingestion/pom.xml ingestion/
COPY matching/pom.xml matching/
COPY suspicion/pom.xml suspicion/
COPY demo/pom.xml demo/
COPY web/pom.xml web/
RUN mvn -B -q -pl web -am dependency:go-offline -DexcludeReactor=true
COPY ledger-core/src ledger-core/src
COPY extraction/src extraction/src
COPY ingestion/src ingestion/src
COPY matching/src matching/src
COPY suspicion/src suspicion/src
COPY demo/src demo/src
COPY web/src web/src
COPY web/third-party web/third-party
# The tests ran in CI's build-and-test job, which this image job depends on.
RUN mvn -B -q -pl web -am -DskipTests -Ddependency-check.skip=true package \
 && mkdir /out \
 && cp web/target/web-*.jar /out/app.jar \
 && chmod 0444 /out/app.jar

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/Deval123/chat-ledger" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.title="chat-ledger"
# Owned by root (directory 0755, jar 0444): the process (uid 65532) cannot change its own code.
# The jar mode is set in the build stage: `COPY --chmod=0444` also gave the created /app directory 0444,
# which cannot be entered (found in stage 2: "Unable to access jarfile").
COPY --from=build --chown=0:0 /out /app
USER 65532:65532
WORKDIR /tmp
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
