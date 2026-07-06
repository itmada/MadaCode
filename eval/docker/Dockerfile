FROM eclipse-temurin:21-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        coreutils \
        curl \
        findutils \
        gawk \
        git \
        grep \
        netcat-openbsd \
        procps \
        sed \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

ENTRYPOINT ["java", "-cp", "/app/MadaCode.jar", "madacode.eval.EvalAttemptMain"]
