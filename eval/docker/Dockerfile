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
        golang-go \
        grep \
        jq \
        netcat-openbsd \
        nodejs \
        npm \
        patch \
        php-cli \
        php-mbstring \
        php-xml \
        composer \
        procps \
        python3 \
        rustc \
        cargo \
        sed \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

ENTRYPOINT ["java", "-cp", "/app/MadaCode.jar", "madacode.eval.EvalAttemptMain"]
