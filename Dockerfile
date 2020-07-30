FROM gcr.io/google-appengine/openjdk
VOLUME /tmp
RUN mkdir /application
COPY . /application
WORKDIR /application
RUN chmod +x /application/mvnw
RUN /application/mvnw install
RUN mv /application/target/*.jar /application/app.jar
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/application/app.jar"]