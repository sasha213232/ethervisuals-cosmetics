# Ether Visuals cosmetics server - single Java file, no external dependencies.
#
# Two stages: compile with a full JDK (javac needs the compiler module, which a JRE-only image does not
# have - that is what made the first version of this Dockerfile fail to deploy), then run the compiled
# class in a lean JRE image, so the final image stays small.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY CosmeticsServer.java .
RUN javac CosmeticsServer.java

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/*.class ./

ENV PORT=8085
ENV ALLOW_OFFLINE=true
EXPOSE 8085

ENTRYPOINT ["java", "CosmeticsServer"]
