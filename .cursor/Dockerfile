FROM ubuntu:24.04

ARG TEMURIN_VERSION=25.0.5+8
ARG TEMURIN_SHA256=68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-${TEMURIN_VERSION}/OpenJDK25U-jdk_x64_linux_hotspot_${TEMURIN_VERSION/+/_}.tar.gz" \
      -o /tmp/openjdk25.tar.gz \
    && echo "${TEMURIN_SHA256}  /tmp/openjdk25.tar.gz" | sha256sum -c - \
    && mkdir -p /usr/lib/jvm \
    && tar -xzf /tmp/openjdk25.tar.gz -C /usr/lib/jvm \
    && rm /tmp/openjdk25.tar.gz \
    && ln -sfn /usr/lib/jvm/jdk-${TEMURIN_VERSION} /usr/lib/jvm/temurin-25 \
    && ln -sf /usr/lib/jvm/temurin-25/bin/java /usr/local/bin/java \
    && ln -sf /usr/lib/jvm/temurin-25/bin/javac /usr/local/bin/javac

ENV JAVA_HOME=/usr/lib/jvm/temurin-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"

USER ubuntu
