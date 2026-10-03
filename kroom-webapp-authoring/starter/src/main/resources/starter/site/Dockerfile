# build with the wrapper this site came with (Gradle 8.x; a `gradle` image would hand us 9), run on a bare JRE
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon --console=plain installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/{{site}} /app
EXPOSE 8080
CMD ["/app/bin/{{site}}"]
