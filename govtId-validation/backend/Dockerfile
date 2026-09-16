# Screening API.
#
# Two stages so the runtime image carries no Maven, no sources and no build cache.
# The dependency layer is resolved before the sources are copied, so editing a Java
# file does not re-download the world on every build.

FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# Wrapper and POM first: this layer is cached until the dependencies themselves change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && mv target/screening-*.jar /build/app.jar


FROM eclipse-temurin:21-jre

# Tesseract supplies the offline OCR path, so a checkpoint with no outbound network
# can still read a document. TesseractOcrEngine probes /usr/bin/tesseract without any
# PATH configuration.
RUN apt-get update && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        curl \
        # libgomp is needed by the OpenCV native binaries the local face fallback loads.
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# Not root: this process accepts uploaded images from the public side of a border post.
RUN useradd --system --create-home --uid 10001 screening
USER screening

WORKDIR /app
COPY --from=build --chown=screening:screening /build/app.jar app.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the container's limit is the real budget,
# and a hard-coded heap either wastes what it was given or dies when the limit is lower.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
