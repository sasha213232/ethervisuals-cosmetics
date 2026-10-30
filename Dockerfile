# Ether Visuals cosmetics server - single Java file, no build step needed.
# `java CosmeticsServer.java` single-file source-launch works directly from a JDK 21+ image.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY CosmeticsServer.java .

ENV PORT=8085
ENV ALLOW_OFFLINE=true
EXPOSE 8085

# Single-file source launch (JEP 330): no separate compile step, no jar to build.
ENTRYPOINT ["java", "CosmeticsServer.java"]
