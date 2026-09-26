# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Keep the build from competing with the rest of the machine for memory.
ENV MAVEN_OPTS="-Xmx512m"
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# all-MiniLM-L6-v2, pinned to one revision and checked by SHA-256 (same values as scripts/fetch-model.sh).
# Downloaded once at build time; the running app never touches the network.
ARG MODEL_REVISION=1110a243fdf4706b3f48f1d95db1a4f5529b4d41
ARG MODEL_SHA256=6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452
ARG VOCAB_SHA256=07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3
RUN mkdir -p /app/models \
 && base="https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/${MODEL_REVISION}" \
 && curl -fsSL --retry 3 -o /app/models/model.onnx "${base}/onnx/model.onnx" \
 && curl -fsSL --retry 3 -o /app/models/vocab.txt "${base}/vocab.txt" \
 && echo "${MODEL_SHA256}  /app/models/model.onnx" | sha256sum -c - \
 && echo "${VOCAB_SHA256}  /app/models/vocab.txt" | sha256sum -c -
ENV RECALL_EMBEDDING_MODEL_DIR=/app/models

COPY --from=build /src/target/recall-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
