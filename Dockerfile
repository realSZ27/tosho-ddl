FROM eclipse-temurin:21-alpine

RUN mkdir /opt/app && \
    mkdir /downloads && \
    mkdir /blackhole

COPY japp.jar /opt/app